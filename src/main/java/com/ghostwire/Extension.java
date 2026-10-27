package com.ghostwire;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import burp.api.montoya.scanner.scancheck.ScanCheckType;
import com.ghostwire.features.AiPayloadGeneratorProvider;
import com.ghostwire.features.AnalyzeMenu;
import com.ghostwire.features.PassiveReview;
import com.ghostwire.features.TechObserver;
import com.ghostwire.features.TriageMenu;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ClientLog;
import com.ghostwire.ui.GhostwireTab;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Extension implements BurpExtension {

    public static final String NAME = "Ghostwire";

    private ExecutorService executor;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);
        GhostwireConfig config = new GhostwireConfig(api.persistence().preferences());
        Prompts prompts = new Prompts(config);
        ClientLog log = new ClientLog() {
            @Override
            public void info(String message) {
                api.logging().logToOutput(message);
            }

            @Override
            public void error(String message) {
                api.logging().logToError(message);
            }
        };
        BedrockConverseClient client = new BedrockConverseClient(config, log);
        ContextBudgeter budgeter = new ContextBudgeter(config);
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ghostwire");
            thread.setDaemon(true);
            return thread;
        });
        GhostwireTab tab = new GhostwireTab(api, config, prompts, client, budgeter, log, executor);
        api.userInterface().registerSuiteTab(NAME, tab.component());
        api.userInterface().registerContextMenuItemsProvider(new AnalyzeMenu(tab.aiView(), tab.repeaterView()));
        api.userInterface().registerContextMenuItemsProvider(new TriageMenu(tab.reportView()));
        api.intruder().registerPayloadGeneratorProvider(new AiPayloadGeneratorProvider(api, prompts, client, budgeter, log));
        api.scanner().registerPassiveScanCheck(new PassiveReview(api, config, prompts, client, budgeter, log), ScanCheckType.PER_REQUEST);
        api.http().registerHttpHandler(new TechObserver(api, tab.techView(), log));
        api.extension().registerUnloadingHandler(() -> {
            tab.repeaterView().stop();
            if (executor != null) {
                executor.shutdownNow();
            }
            api.logging().logToOutput(NAME + " unloaded");
        });
        api.logging().logToOutput(NAME + " loaded");
    }
}
