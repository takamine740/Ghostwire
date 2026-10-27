package com.ghostwire.ui;

import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ClientLog;
import com.ghostwire.llm.ConverseResult;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/** Settings form on the Ghostwire suite tab. Reads and writes Burp user preferences. */
public final class SettingsView {

    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern PROXY_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");

    private final GhostwireConfig config;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ClientLog log;
    private final ExecutorService executor;

    private final JPasswordField apiKey = new JPasswordField(36);
    private final JTextField region = new JTextField(20);
    private final JTextField modelId = new JTextField(36);
    private final JSpinner maxTokens = new JSpinner(new SpinnerNumberModel(GhostwireConfig.DEFAULT_MAX_TOKENS, 1, 200_000, 256));
    private final JTextField proxyHost = new JTextField(20);
    private final JSpinner proxyPort = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JCheckBox redact = new JCheckBox("Redact Authorization, Cookie, and token values before they are sent");
    private final JCheckBox passive = new JCheckBox("Passive scan: review in-scope text responses during a Burp passive scan");
    private final JSpinner maxInputChars = new JSpinner(new SpinnerNumberModel(GhostwireConfig.DEFAULT_MAX_INPUT_CHARS, 2000, 500_000, 1000));
    private final JComboBox<String> promptPicker = new JComboBox<>(new String[]{"System", "Analyze", "Intruder", "Repeater", "Passive", "Report"});
    private final JTextArea prompt = new JTextArea(14, 60);
    private final Map<String, String> promptDrafts = new HashMap<>();
    private String editingPrompt = Prompts.SYSTEM;
    private final JTextArea status = new JTextArea(4, 60);
    private final JButton testButton = new JButton("Test connection");

    public SettingsView(GhostwireConfig config, Prompts prompts, BedrockConverseClient client, ClientLog log, ExecutorService executor) {
        this.config = config;
        this.prompts = prompts;
        this.client = client;
        this.log = log;
        this.executor = executor;
        loadFromConfig();
    }

    public JComponent component() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(note("Paste the Bedrock API key in the first field. It is stored in Burp's user preferences on this machine, not in the project file. "
                + "A short-term key expires; paste a new one in the same field when it does. "
                + "Region for this team is us-west-2. Copy the model ID from the Bedrock console."), c);

        c.gridwidth = 1;
        addRow(form, c, "Bedrock API key", apiKey);
        addRow(form, c, "Region", region);
        addRow(form, c, "Model ID", modelId);
        addRow(form, c, "Max tokens", maxTokens);
        addRow(form, c, "Proxy host", proxyHost);
        addRow(form, c, "Proxy port (0 = none)", proxyPort);
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy++;
        form.add(redact, c);
        c.gridy++;
        form.add(passive, c);
        c.gridwidth = 1;
        addRow(form, c, "Max input characters", maxInputChars);

        addRow(form, c, "Prompt", promptPicker);
        promptPicker.addActionListener(e -> {
            String next = promptName((String) promptPicker.getSelectedItem());
            if (next.equals(editingPrompt)) {
                return;
            }
            promptDrafts.put(editingPrompt, prompt.getText());
            editingPrompt = next;
            prompt.setText(promptDrafts.containsKey(next) ? promptDrafts.get(next) : prompts.get(next));
        });
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy++;
        form.add(new JLabel("System uses {{SCOPE}}. Analyze uses {{EXCHANGE}}. Intruder uses {{BASE}}, {{CONTEXT}}, and {{REQUEST}}. Repeater uses {{EXCHANGE}} and {{HISTORY}}. Passive uses {{EXCHANGE}}. Report uses the issue fields and {{EXCHANGE}}."), c);
        c.gridy++;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        form.add(new JScrollPane(prompt), c);
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;

        JPanel buttons = new JPanel();
        JButton saveButton = new JButton("Save");
        JButton resetPrompt = new JButton("Reset prompt");
        saveButton.addActionListener(e -> save(false));
        testButton.addActionListener(e -> save(true));
        resetPrompt.addActionListener(e -> {
            prompts.reset(editingPrompt);
            promptDrafts.remove(editingPrompt);
            prompt.setText(Prompts.bundled(editingPrompt));
            setStatus(promptPicker.getSelectedItem() + " prompt reset to the bundled default.");
        });
        buttons.add(saveButton);
        buttons.add(testButton);
        buttons.add(resetPrompt);
        c.gridy++;
        form.add(buttons, c);

        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        c.gridy++;
        c.weighty = 0.2;
        c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(status), c);

        JPanel root = new JPanel(new BorderLayout());
        root.add(new JScrollPane(form), BorderLayout.CENTER);
        return root;
    }

    private void loadFromConfig() {
        apiKey.setText(config.apiKey());
        region.setText(config.region());
        modelId.setText(config.modelId());
        maxTokens.setValue(config.maxTokens());
        proxyHost.setText(config.proxyHost());
        proxyPort.setValue(config.proxyPort());
        redact.setSelected(config.redactionEnabled());
        passive.setSelected(config.passiveEnabled());
        maxInputChars.setValue(config.maxInputChars());
        prompt.setText(prompts.get(Prompts.SYSTEM));
    }

    private void save(boolean thenTest) {
        commit(maxTokens);
        commit(proxyPort);
        commit(maxInputChars);
        String error = validate();
        if (error != null) {
            setStatus(error);
            return;
        }
        config.setApiKey(new String(apiKey.getPassword()));
        config.setRegion(region.getText().trim());
        config.setModelId(modelId.getText().trim());
        config.setMaxTokens(((Number) maxTokens.getValue()).intValue());
        config.setProxyHost(proxyHost.getText().trim());
        config.setProxyPort(((Number) proxyPort.getValue()).intValue());
        config.setRedactionEnabled(redact.isSelected());
        config.setPassiveEnabled(passive.isSelected());
        config.setMaxInputChars(((Number) maxInputChars.getValue()).intValue());
        promptDrafts.put(editingPrompt, prompt.getText());
        for (Map.Entry<String, String> entry : promptDrafts.entrySet()) {
            prompts.save(entry.getKey(), entry.getValue());
        }
        if (!thenTest) {
            setStatus("Settings saved.");
            return;
        }
        testButton.setEnabled(false);
        setStatus("Testing connection...");
        executor.submit(() -> {
            String message;
            try {
                ConverseResult result = client.testConnection();
                String reply = result.text().isBlank() ? "(no text, stop reason " + result.stopReason() + ")" : abbreviate(result.text(), 80);
                message = "Connected. Model replied: " + reply
                        + " (input " + result.inputTokens() + " tokens, output " + result.outputTokens() + "). Settings saved.";
                log.info("Ghostwire: connection test succeeded.");
            } catch (Exception ex) {
                message = "Connection failed: " + client.safeMessage(ex);
                log.error("Ghostwire: connection test failed: " + client.safeMessage(ex));
            }
            String shown = message;
            SwingUtilities.invokeLater(() -> {
                setStatus(shown);
                testButton.setEnabled(true);
            });
        });
    }

    private String validate() {
        if (apiKey.getPassword().length == 0) {
            return "Set a Bedrock API key.";
        }
        String regionText = region.getText().trim();
        if (!REGION.matcher(regionText).matches()) {
            return "Set a Bedrock region such as us-east-1.";
        }
        String model = modelId.getText().trim();
        if (model.isEmpty() || model.chars().anyMatch(Character::isWhitespace)) {
            return "Set the Bedrock model ID or inference profile. Ghostwire does not guess this.";
        }
        int tokens = ((Number) maxTokens.getValue()).intValue();
        if (tokens < 1 || tokens > 200_000) {
            return "Max tokens must be between 1 and 200000.";
        }
        String host = proxyHost.getText().trim();
        int port = ((Number) proxyPort.getValue()).intValue();
        if (!host.isEmpty() && (!PROXY_HOST.matcher(host).matches() || port < 1)) {
            return "Set a proxy host and a port from 1 to 65535, or leave the host blank.";
        }
        return null;
    }

    private static String promptName(String label) {
        return switch (label) {
            case "Analyze" -> Prompts.ANALYZE;
            case "Intruder" -> Prompts.INTRUDER;
            case "Repeater" -> Prompts.REPEATER;
            case "Passive" -> Prompts.PASSIVE;
            case "Report" -> Prompts.REPORT;
            default -> Prompts.SYSTEM;
        };
    }

    private static JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setRows(3);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(null);
        return area;
    }

    private static void addRow(JPanel form, GridBagConstraints c, String label, JComponent field) {
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setCaretPosition(0);
    }

    private static void commit(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (ParseException ignored) {
            // Keep the last committed value.
        }
    }

    private static String abbreviate(String text, int max) {
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
