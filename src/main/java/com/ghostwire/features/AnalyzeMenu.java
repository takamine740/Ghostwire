package com.ghostwire.features;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.ghostwire.ui.AiView;
import com.ghostwire.ui.RepeaterView;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.List;

/** Right-click entry on Proxy, Repeater, the site map, and other message views. */
public final class AnalyzeMenu implements ContextMenuItemsProvider {

    private final AiView ai;
    private final RepeaterView repeater;

    public AnalyzeMenu(AiView ai, RepeaterView repeater) {
        this.ai = ai;
        this.repeater = repeater;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        HttpRequestResponse item = selected(event);
        if (item == null) {
            return List.of();
        }
        try {
            if (item.request() == null) {
                return List.of();
            }
        } catch (RuntimeException ex) {
            return List.of();
        }
        JMenuItem analyze = new JMenuItem("Ghostwire: Analyze with AI");
        analyze.addActionListener(e -> ai.analyze(item));
        JMenuItem loop = new JMenuItem("Ghostwire: Repeater loop");
        loop.addActionListener(e -> repeater.start(item));
        return List.of(analyze, loop);
    }

    private static HttpRequestResponse selected(ContextMenuEvent event) {
        if (event.messageEditorRequestResponse().isPresent()
                && event.messageEditorRequestResponse().get().requestResponse() != null) {
            return event.messageEditorRequestResponse().get().requestResponse();
        }
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        return selected.get(0);
    }
}
