package com.ghostwire;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class Extension implements BurpExtension {

    public static final String NAME = "Ghostwire";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);
        api.extension().registerUnloadingHandler(() -> api.logging().logToOutput(NAME + " unloaded"));
        api.logging().logToOutput(NAME + " loaded");
    }
}
