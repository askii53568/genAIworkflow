package com.xchange.core.workflows;
import com.adobe.granite.workflow.*;
import com.adobe.granite.workflow.exec.*;
import com.adobe.granite.workflow.metadata.*;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;


@Slf4j
@Component(service = WorkflowProcess.class, property = {"process.label=Generate Image via DALL-E"})
public class GenerateImageWF implements WorkflowProcess {
    @Override
    public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {

    }
}
