package com.ghostwire.ui;

import burp.api.montoya.MontoyaApi;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ClientLog;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import java.util.concurrent.ExecutorService;

/** Suite tab. AI holds analysis and chat. Repeater holds the agent loop. Settings holds the connection. */
public final class GhostwireTab {

    private final JTabbedPane tabs = new JTabbedPane();
    private final AiView ai;
    private final RepeaterView repeater;
    private final ReportView report;
    private final TechView technologies;

    public GhostwireTab(MontoyaApi api, GhostwireConfig config, Prompts prompts, BedrockConverseClient client,
                        ContextBudgeter budgeter, ClientLog log, ExecutorService executor) {
        ai = new AiView(api, prompts, client, budgeter, log, executor, () -> tabs.setSelectedIndex(1));
        repeater = new RepeaterView(api, config, prompts, client, budgeter, log, executor, () -> tabs.setSelectedIndex(2));
        report = new ReportView(api, config, prompts, client, budgeter, log, executor, () -> tabs.setSelectedIndex(3));
        technologies = new TechView(api, log);
        tabs.addTab("Guide", new GuideView().component());
        tabs.addTab("AI", ai.component());
        tabs.addTab("Repeater", repeater.component());
        tabs.addTab("Report", report.component());
        tabs.addTab("Technologies", technologies.component());
        tabs.addTab("Settings", new SettingsView(config, prompts, client, log, executor).component());
        api.userInterface().applyThemeToComponent(tabs);
    }

    public JComponent component() {
        return tabs;
    }

    public AiView aiView() {
        return ai;
    }

    public RepeaterView repeaterView() {
        return repeater;
    }

    public ReportView reportView() {
        return report;
    }

    public TechView techView() {
        return technologies;
    }
}
