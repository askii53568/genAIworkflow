package com.xchange.core.workflows;
import com.adobe.granite.workflow.*;
import com.adobe.granite.workflow.exec.*;
import com.adobe.granite.workflow.metadata.*;
import com.day.cq.dam.api.*;
import com.xchange.core.utils.*;
import lombok.extern.slf4j.Slf4j;
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

import static com.day.cq.dam.api.DamConstants.NT_DAM_ASSET;
import static com.xchange.core.utils.SecretVariables.AUTH_HEADER;
import static com.xchange.core.utils.SecretVariables.COOKIE_HEADER;
import static org.apache.sling.jcr.resource.api.JcrResourceConstants.NT_SLING_FOLDER;

@Slf4j
@Component(service = WorkflowProcess.class, property = {"process.label=Generate Image via DALL-E"})
public class GenerateImageWF implements WorkflowProcess {

    // OpenAI API endpoints
    private static final String IMAGE_VARIATIONS_API_ENDPOINT = "https://api.openai.com/v1/images/variations";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
        String imageDAMPath = "";
        String folderPath = "";
        String payloadPath = item.getWorkflowData().getPayload().toString();
        ResourceResolver resolver = session.adaptTo(ResourceResolver.class);
        Resource resource = resolver.getResource(payloadPath);
        if (resource == null) return;

        if (resource.isResourceType(NT_DAM_ASSET)) {
            imageDAMPath = resource.getPath();
            //check if the image is less than 4MB and is also a PNG
            //then throw workflow exception "Uploaded image must be a PNG and less than 4 MB."
        } else {
            return;
        }
        Resource folder = resource.getParent();
        if(folder != null && folder.isResourceType(NT_SLING_FOLDER)){
            folderPath = folder.getPath();
        }
        int resourceID = Math.abs(resource.getPath().hashCode() - 1);
        InputStream inputStream = getImageAsStream(imageDAMPath, resolver);
        ImageResponse imageResponse = uploadImageWithFormData(inputStream, "og-img-" + resourceID);

        if(imageResponse != null && imageResponse.getError() != null)
        {
            log.error(imageResponse.getError().getMessage());
        }else {
            String imageURL = imageResponse.getData().get(0).getUrl();
            String imageName = "ai-img-" + resourceID;
            InputData inputData = new InputData(folderPath, imageName, imageURL);
            saveImageToDAM(resolver, inputData);
        }
    }

    public InputStream getImageAsStream(String damPath, ResourceResolver resolver){
        try {
            Resource resource = resolver.getResource(damPath + "/jcr:content/renditions/original/jcr:content");
            if (resource != null) {
                Node node = resource.adaptTo(Node.class);
                if (node != null) {
                    Binary binary = node.getProperty("jcr:data").getBinary();
                    return binary.getStream();
                }
            }
        } catch (RepositoryException | IllegalStateException e){
            log.error("Error in converting DAM image to input stream: "+ e.getMessage());
        }
        return null;
    }

    public static ImageResponse uploadImageWithFormData(InputStream inputStream, String imageName) {

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost uploadFile = new HttpPost(IMAGE_VARIATIONS_API_ENDPOINT);
            uploadFile.setHeader("Authorization", AUTH_HEADER);
            uploadFile.setHeader("Cookie", COOKIE_HEADER);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addBinaryBody("image", inputStream, ContentType.APPLICATION_OCTET_STREAM, imageName);
            builder.addTextBody("model", "dall-e-2");
            builder.addTextBody("n", "1");
            builder.addTextBody("size", "1024x1024");

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
            String fileExt = mimeType.replaceAll("image/", "");
            String imagePath = inputData.getDamfolderpath() + "/" + inputData.getImagename() + "." + fileExt;

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
