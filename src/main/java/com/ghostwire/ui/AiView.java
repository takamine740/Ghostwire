package com.ghostwire.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.context.TextBudget;
import com.ghostwire.features.ChatSession;
import com.ghostwire.features.ChatStore;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ChatMessage;
import com.ghostwire.llm.ClientLog;
import com.ghostwire.llm.ConverseResult;

import javax.swing.AbstractAction;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Analysis results and follow-up chat. */
public final class AiView {

    private static final String EMPTY = "Right-click an in-scope request in Proxy, Repeater, or the site map and choose Ghostwire: Analyze with AI.";

    private final MontoyaApi api;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;
    private final ExecutorService executor;
    private final Runnable showTab;
    private final ChatStore store;

    private final DefaultListModel<ChatSession> sessions = new DefaultListModel<>();
    private final JList<ChatSession> list = new JList<>(sessions);
    private final JLabel title = new JLabel("No analysis yet");
    private final JTextArea transcript = new JTextArea();
    private final JTextArea followUp = new JTextArea(3, 40);
    private final JButton send = new JButton("Send follow-up");
    private final JLabel status = new JLabel(" ");
    private boolean busy;

    public AiView(MontoyaApi api, Prompts prompts, BedrockConverseClient client, ContextBudgeter budgeter,
                  ClientLog log, ExecutorService executor, Runnable showTab) {
        this.api = api;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
        this.executor = executor;
        this.showTab = showTab;
        this.store = new ChatStore(api.persistence().extensionData());
        for (ChatSession session : store.load()) {
            sessions.addElement(session);
        }
    }

    public JComponent component() {
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        transcript.setText(EMPTY);
        followUp.setLineWrap(true);
        followUp.setWrapStyleWord(true);
        followUp.setEnabled(false);
        send.setEnabled(false);

        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                show(list.getSelectedValue());
            }
        });
        if (!sessions.isEmpty()) {
            list.setSelectedIndex(0);
        }

        send.addActionListener(e -> sendFollowUp());
        followUp.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "send-follow-up");
        followUp.getActionMap().put("send-follow-up", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendFollowUp();
            }
        });

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(240, 200));

        JPanel conversation = new JPanel(new BorderLayout(6, 6));
        conversation.add(title, BorderLayout.NORTH);
        conversation.add(new JScrollPane(transcript), BorderLayout.CENTER);

        JPanel compose = new JPanel(new BorderLayout(6, 6));
        compose.add(new JScrollPane(followUp), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(send);
        compose.add(buttons, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.add(compose, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        conversation.add(south, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.add(listScroll, BorderLayout.WEST);
        root.add(conversation, BorderLayout.CENTER);
        return root;
    }

    public void analyze(HttpRequestResponse item) {
        showTab.run();
        HttpRequest request;
        try {
            request = item.request();
        } catch (RuntimeException ex) {
            setStatus("Could not read that request.");
            return;
        }
        if (request == null) {
            setStatus("No request was selected.");
            return;
        }
        String url;
        try {
            url = request.url();
        } catch (RuntimeException ex) {
            setStatus("That request has no URL Ghostwire can check against scope.");
            return;
        }
        String titleText;
        try {
            String path = request.path();
            if (path.length() > 80) {
                path = path.substring(0, 80) + "...";
            }
            titleText = request.method() + " " + path;
        } catch (RuntimeException ex) {
            titleText = url;
        }
        HttpResponse response = item.hasResponse() ? item.response() : null;
        boolean inScope = api.scope().isInScope(url);
        if (inScope && busy) {
            setStatus("Another analysis is still running.");
            return;
        }
        ChatSession session = new ChatSession(titleText, url);
        sessions.add(0, session);
        list.setSelectedIndex(0);
        if (!inScope) {
            session.setNote("Not sent. This URL is outside Burp's target scope. Add it under Target → Scope, then analyze it again.\n" + url);
            show(session);
            store.save(snapshot());
            setStatus("Not sent. The request is outside Burp's target scope.");
            return;
        }
        busy = true;
        send.setEnabled(false);
        followUp.setEnabled(false);
        setStatus("Analyzing " + titleText + "...");
        log.info("Ghostwire: analyzing " + TextBudget.redactSecrets(url));
        executor.submit(() -> {
            try {
                String exchange = budgeter.formatExchange(request, response);
                String template = prompts.get(Prompts.ANALYZE);
                String userText = template.contains("{{EXCHANGE}}")
                        ? template.replace("{{EXCHANGE}}", exchange)
                        : template + "\n\nHTTP exchange:\n" + exchange;
                ConverseResult result = client.converse(List.of(new ChatMessage("user", userText)), prompts.systemPrompt(url));
                SwingUtilities.invokeLater(() -> {
                    session.add(new ChatMessage("user", userText));
                    session.add(new ChatMessage("assistant", result.text().isBlank()
                            ? "(no text, stop reason " + result.stopReason() + ")"
                            : result.text()));
                    store.save(snapshot());
                    if (list.getSelectedValue() == session) {
                        show(session);
                    }
                    busy = false;
                    send.setEnabled(true);
                    followUp.setEnabled(true);
                    setStatus("Analysis finished. Input " + result.inputTokens() + " tokens, output " + result.outputTokens() + ".");
                    log.info("Ghostwire: analysis finished.");
                });
            } catch (Exception ex) {
                String message = client.safeMessage(ex);
                SwingUtilities.invokeLater(() -> {
                    session.setNote("Analysis failed: " + message);
                    if (list.getSelectedValue() == session) {
                        show(session);
                    }
                    busy = false;
                    send.setEnabled(true);
                    followUp.setEnabled(list.getSelectedValue() != null);
                    setStatus("Analysis failed: " + message);
                    log.error("Ghostwire: analysis failed: " + message);
                });
            }
        });
    }

    private void sendFollowUp() {
        ChatSession session = list.getSelectedValue();
        String text = followUp.getText().trim();
        if (session == null || text.isEmpty() || busy) {
            return;
        }
        if (session.messages().isEmpty()) {
            setStatus("This analysis has no reply to follow up on.");
            return;
        }
        busy = true;
        send.setEnabled(false);
        followUp.setText("");
        setStatus("Waiting for the model...");
        List<ChatMessage> history = new ArrayList<>(session.messages());
        history.add(new ChatMessage("user", text));
        List<ChatMessage> fitted = budgeter.fitHistory(history);
        executor.submit(() -> {
            try {
                ConverseResult result = client.converse(fitted, prompts.systemPrompt(session.url()));
                SwingUtilities.invokeLater(() -> {
                    session.add(new ChatMessage("user", text));
                    session.add(new ChatMessage("assistant", result.text().isBlank()
                            ? "(no text, stop reason " + result.stopReason() + ")"
                            : result.text()));
                    store.save(snapshot());
                    if (list.getSelectedValue() == session) {
                        show(session);
                    }
                    busy = false;
                    send.setEnabled(true);
                    setStatus("Reply received. Input " + result.inputTokens() + " tokens, output " + result.outputTokens() + ".");
                });
            } catch (Exception ex) {
                String message = client.safeMessage(ex);
                SwingUtilities.invokeLater(() -> {
                    followUp.setText(text);
                    busy = false;
                    send.setEnabled(true);
                    setStatus("Follow-up failed: " + message);
                    log.error("Ghostwire: follow-up failed: " + message);
                });
            }
        });
    }

    private void show(ChatSession session) {
        if (session == null) {
            title.setText("No analysis yet");
            transcript.setText(EMPTY);
            followUp.setEnabled(false);
            return;
        }
        title.setText(session.title());
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : session.messages()) {
            text.append("user".equals(message.role()) ? "You" : "Ghostwire").append("\n");
            text.append(message.text()).append("\n\n");
        }
        if (session.note() != null && !session.note().isBlank()) {
            text.append(session.note());
        }
        if (text.isEmpty()) {
            text.append("Working...");
        }
        transcript.setText(text.toString());
        transcript.setCaretPosition(0);
        followUp.setEnabled(!busy);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private List<ChatSession> snapshot() {
        List<ChatSession> copy = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            copy.add(sessions.get(i));
        }
        return copy;
    }
}
