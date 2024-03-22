package com.xchange.core.workflows;
import com.adobe.granite.workflow.*;
import com.adobe.granite.workflow.exec.*;
import com.adobe.granite.workflow.metadata.*;
import com.day.cq.dam.api.*;
import com.xchange.core.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.jackrabbit.commons.*;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import java.io.IOException;

import org.apache.sling.api.resource.Resource;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.util.EntityUtils;

import javax.jcr.*;

import static com.day.cq.commons.jcr.JcrConstants.JCR_DATA;
import static com.day.cq.dam.api.DamConstants.DAM_SIZE;
import static com.day.cq.dam.api.DamConstants.DC_FORMAT;
import static com.day.cq.dam.api.DamConstants.NT_DAM_ASSET;
import static com.xchange.core.utils.SecretVariables.AUTH_HEADER;
import static com.xchange.core.utils.SecretVariables.COOKIE_HEADER;

@Slf4j
@Component(service = WorkflowProcess.class, property = {"process.label=Generate Image via DALL-E"})
public class GenerateImageWF implements WorkflowProcess {

    // OpenAI API endpoints
    private static final String IMAGE_VARIATIONS_API_ENDPOINT = "https://api.openai.com/v1/images/variations";

    private static final String AI_IMAGE_PREFIX = "ai-img-";

    private static final String IMAGE_SIZE = "1024x1024";

    private static final String AI_MODEL = "dall-e-2";

    private static final String IMAGE_COUNT = "1";

    private static final String AI_GALLERY_PATH = "/content/dam/xchange/ai-portrait-gallery/";

    private static final String OG_REDNITION_PATH = "jcr:content/renditions/original/jcr:content";

    public static final String JCR_METADATA_NODE = "jcr:content/metadata";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
        Node assetNode = null;
        String payloadPath = item.getWorkflowData().getPayload().toString();
        ResourceResolver resolver = session.adaptTo(ResourceResolver.class);
        Resource resource = resolver.getResource(payloadPath);
        if (resource == null) return;

        if (resource.isResourceType(NT_DAM_ASSET)) {
            assetNode = resource.adaptTo(Node.class);
            if(assetNode == null || !checkImageValid(assetNode)){
                throw new WorkflowException("Uploaded image must be a PNG and less than 4 MB.");
            }
        }
        //come up with a way to do normal resoource ID
        int resourceID = Math.abs(resource.getName().hashCode() - 1);
        InputStream inputStream = getImageAsStream(assetNode);
        ImageResponse imageResponse = uploadImageWithFormData(inputStream, AI_IMAGE_PREFIX + resourceID);

        if(imageResponse != null && imageResponse.getError() != null){
            log.error(imageResponse.getError().getMessage());
        }else {
            String imageURL = imageResponse.getData().get(0).getUrl();
            String imageName = AI_IMAGE_PREFIX + resourceID;
            InputData inputData = new InputData(imageName, imageURL);
            saveImageToDAM(resolver, inputData);
        }
    }

    private boolean checkImageValid(Node assetNode){
        try {
            //check if metadata child node exists
            if (JcrUtils.getNodeIfExists(assetNode, JCR_METADATA_NODE) == null) {
                return false;
            }
            Node metadataNode = assetNode.getNode(JCR_METADATA_NODE);

            //check if system extension exists
            if (!metadataNode.hasProperty(DC_FORMAT)) {
                return false;
            }
            //get system extension property i.e (application/zip)
            String dcformatProperty = metadataNode.getProperty(DC_FORMAT).getString();
            long assetSize = metadataNode.getProperty(DAM_SIZE).getLong();

            boolean fileExtCheck = dcformatProperty.substring(dcformatProperty.lastIndexOf('/') + 1).equals("png");
            long fourMBInBytes = 4L << 20;
            boolean fileSizeCheck = assetSize < fourMBInBytes;

            return fileExtCheck && fileSizeCheck;
        } catch (RepositoryException e) {
            return false;
        }
    }

    private InputStream getImageAsStream(Node assetNode){
        try {
            if (JcrUtils.getNodeIfExists(assetNode, OG_REDNITION_PATH) == null) {
                return null;
            }
            Node ogAssetNode = assetNode.getNode(OG_REDNITION_PATH);
            if (ogAssetNode != null) {
                Binary binary = ogAssetNode.getProperty(JCR_DATA).getBinary();
                return binary.getStream();
            }
        } catch (RepositoryException | IllegalStateException e){
            log.error("Error in converting DAM image to input stream: "+ e.getMessage());
        }
        return null;
    }

    private static ImageResponse uploadImageWithFormData(InputStream inputStream, String imageName) {

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost uploadFile = new HttpPost(IMAGE_VARIATIONS_API_ENDPOINT);
            uploadFile.setHeader("Authorization", AUTH_HEADER);
            uploadFile.setHeader("Cookie", COOKIE_HEADER);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addBinaryBody("image", inputStream, ContentType.APPLICATION_OCTET_STREAM, imageName);
            builder.addTextBody("model", AI_MODEL);
            builder.addTextBody("n", IMAGE_COUNT);
            builder.addTextBody("size", IMAGE_SIZE);

            HttpEntity multipart = builder.build();
            uploadFile.setEntity(multipart);

            try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
                if(response.getStatusLine().getStatusCode() == 200){
                    return MAPPER.readValue(EntityUtils.toString(response.getEntity()), ImageResponse.class);
                } else {
                    log.error("Error while creating renditions of image: ");
                }
            }
        } catch (IOException e) {
            log.error("Error while creating renditions of image: "+ e.getMessage());
        }
        return null;
    }

    // Save an image to the AEM DAM
    private void saveImageToDAM(ResourceResolver resourceResolver, InputData inputData) {

        InputStream inputStream = null;
        String mimeType = "";
        try {
            // Open a connection to the remote image URL
            URL url = new URL(inputData.getRemoteImageURL());
            URLConnection uCon = url.openConnection();
            inputStream = uCon.getInputStream();
            mimeType = uCon.getContentType();

            // Create the asset in the DAM
            String fileExt = mimeType.replace("image/", "");
            String imagePath = AI_GALLERY_PATH + inputData.getImagename() + "." + fileExt;

            Objects.requireNonNull(resourceResolver.adaptTo(AssetManager.class)).createAsset(imagePath, inputStream, mimeType, true);

        } catch (Exception e) {
            log.error("Error uploading generated image into DAM: "+ e.getMessage());
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                log.error("Couldn't close input stream: "+ e.getMessage());
            }
        }
    }
}
