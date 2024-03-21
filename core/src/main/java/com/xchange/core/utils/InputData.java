package com.xchange.core.utils;

import lombok.*;

@Getter
public class InputData {

    private String imagename;
    private String remoteImageURL;
    public InputData(String imagename, String remoteImageURL){

        this.imagename = imagename;
        this.remoteImageURL = remoteImageURL;
    }

}