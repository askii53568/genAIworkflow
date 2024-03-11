package com.xchange.core.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Getter
public class InputData {

    private String damfolderpath;
    private String imagename;
    private String remoteImageURL;
    public InputData(String damfolderpath, String imagename, String remoteImageURL){

        this.damfolderpath = damfolderpath;
        this.imagename = imagename;
        this.remoteImageURL = remoteImageURL;
    }

}