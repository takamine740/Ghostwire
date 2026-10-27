package com.ghostwire.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.features.AuditEntry;
import com.ghostwire.features.AuditLog;
import com.ghostwire.features.Decision;
import com.ghostwire.features.RepeaterControls;
import com.ghostwire.features.RepeaterLimits;
import com.ghostwire.features.RepeaterLoop;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ClientLog;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Repeater loop: proposed request, approval buttons, audit log, and the kill switch. */
public final class RepeaterView implements RepeaterControls {

    private final MontoyaApi api;
    private final GhostwireConfig config;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;
    private final ExecutorService executor;
    private final Runnable showTab;

    private final List<AuditEntry> entries = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Time", "Result", "Method", "URL", "Status", "Reason"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextArea proposal = new JTextArea();
    private final JTextArea reason = new JTextArea(2, 40);
    private final JTextArea status = plain(" ", 2);
    private final JCheckBox approveEach = new JCheckBox("Approve each request before it is sent");
    private final JSpinner maxRequests;
    private final JSpinner intervalSeconds;
    private final JButton sendButton = new JButton("Send this request");
    private final JButton skipButton = new JButton("Skip");
    private final JButton stopButton = new JButton("Stop loop");
    private final JButton exportButton = new JButton("Export audit log");
    private final BlockingQueue<Decision> decisions = new ArrayBlockingQueue<>(1);

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean kill = new java.util.concurrent.atomic.AtomicBoolean();
    private int runToken;

    private JComponent root;

    public RepeaterView(MontoyaApi api, GhostwireConfig config, Prompts prompts, BedrockConverseClient client,
                        ContextBudgeter budgeter, ClientLog log, ExecutorService executor, Runnable showTab) {
        this.api = api;
        this.config = config;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
        this.executor = executor;
        this.showTab = showTab;
        this.maxRequests = new JSpinner(new SpinnerNumberModel(config.agentMaxRequests(), 1, 20, 1));
        this.intervalSeconds = new JSpinner(new SpinnerNumberModel(config.agentIntervalMs() / 1000, 0, 30, 1));
        this.approveEach.setSelected(config.agentApprovalRequired());
        entries.addAll(AuditLog.fromJson(api.persistence().extensionData().getString(AuditLog.KEY)));
        for (AuditEntry entry : entries) {
            addRow(entry);
        }
    }

    public JComponent component() {
        if (root != null) {
            return root;
        }
        proposal.setEditable(false);
        proposal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        proposal.setText("No proposal yet.");
        reason.setEditable(false);
        reason.setLineWrap(true);
        reason.setWrapStyleWord(true);
        reason.setOpaque(false);
        sendButton.setEnabled(false);
        skipButton.setEnabled(false);
        stopButton.setEnabled(false);
        sendButton.addActionListener(e -> choose(Decision.SEND));
        skipButton.addActionListener(e -> choose(Decision.SKIP));
        stopButton.addActionListener(e -> stop());
        exportButton.addActionListener(e -> export());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(approveEach);
        controls.add(new JLabel("Max requests"));
        controls.add(maxRequests);
        controls.add(new JLabel("Seconds between sends"));
        controls.add(intervalSeconds);
        controls.add(stopButton);
        controls.add(exportButton);

        JPanel north = new JPanel(new BorderLayout());
        north.add(plain("Right-click an in-scope request and choose Ghostwire: Repeater loop. "
                + "The model proposes one next request. Ghostwire keeps the original host, stays inside Burp scope, "
                + "and does not follow redirects. GET, HEAD, and OPTIONS wait for a click unless you turn approval off. "
                + "POST, PUT, DELETE, and PATCH always wait. Stop loop cancels the task.", 4), BorderLayout.NORTH);
        north.add(controls, BorderLayout.CENTER);
        north.add(status, BorderLayout.SOUTH);

        JPanel proposalPanel = new JPanel(new BorderLayout());
        proposalPanel.add(reason, BorderLayout.NORTH);
        proposalPanel.add(new JScrollPane(proposal), BorderLayout.CENTER);
        JPanel decisionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        decisionButtons.add(sendButton);
        decisionButtons.add(skipButton);
        proposalPanel.add(decisionButtons, BorderLayout.SOUTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, proposalPanel, new JScrollPane(table));
        split.setResizeWeight(0.55);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        root = panel;
        return root;
    }

    public void start(HttpRequestResponse item) {
        component();
        showTab.run();
        if (!running.compareAndSet(false, true)) {
            status.setText("A Repeater loop is already running.");
            return;
        }
        kill.set(false);
        decisions.clear();
        int token = ++runToken;
        int max = spinner(maxRequests, config.agentMaxRequests());
        int seconds = spinner(intervalSeconds, config.agentIntervalMs() / 1000);
        boolean approve = approveEach.isSelected();
        config.setAgentApprovalRequired(approve);
        config.setAgentMaxRequests(max);
        config.setAgentIntervalMs(seconds * 1000);
        approveEach.setEnabled(false);
        maxRequests.setEnabled(false);
        intervalSeconds.setEnabled(false);
        stopButton.setEnabled(true);
        sendButton.setEnabled(false);
        skipButton.setEnabled(false);
        proposal.setText("");
        reason.setText("");
        status.setText(approve
                ? "Starting. Nothing is sent until you approve it."
                : "Starting. Safe methods can send without a click. Other methods still wait.");
        RepeaterLimits limits = new RepeaterLimits(max, seconds * 1000, approve);
        executor.submit(() -> {
            try {
                new RepeaterLoop(api, prompts, client, budgeter, log).run(item, limits, this);
            } catch (RuntimeException ex) {
                String message = client.safeMessage(ex);
                log.error("Ghostwire: Repeater stopped. " + message);
                setStatus("Repeater stopped. " + message);
            } finally {
                SwingUtilities.invokeLater(() -> finished(token));
            }
        });
    }

    /** Kill switch. Safe to call from the Swing thread or while the extension is unloading. */
    public void stop() {
        kill.set(true);
        decisions.offer(Decision.STOP);
        SwingUtilities.invokeLater(() -> {
            sendButton.setEnabled(false);
            skipButton.setEnabled(false);
            if (running.get()) {
                status.setText("Stopping...");
            }
        });
    }

    @Override
    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text == null ? "" : text));
    }

    @Override
    public void record(String result, String method, String url, String statusCode, String why) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
            AuditEntry entry = AuditEntry.of(time, result, method, url, statusCode, why);
            entries.add(entry);
            addRow(entry);
            while (entries.size() > AuditLog.MAX_ENTRIES) {
                entries.remove(0);
                model.removeRow(0);
            }
            save();
            int last = model.getRowCount() - 1;
            if (last >= 0) {
                table.scrollRectToVisible(table.getCellRect(last, 0, true));
            }
        });
    }

    @Override
    public Decision decide(String method, String url, String why, String rawRequest, boolean waitForApproval)
            throws InterruptedException {
        decisions.clear();
        SwingUtilities.invokeLater(() -> {
            proposal.setText(rawRequest == null ? "" : rawRequest);
            proposal.setCaretPosition(0);
            reason.setText(why == null || why.isBlank() ? "" : why);
            boolean enable = waitForApproval && !kill.get();
            sendButton.setEnabled(enable);
            skipButton.setEnabled(enable);
            status.setText(waitForApproval
                    ? "Approval needed for " + method + " " + url
                    : "Sending " + method + " " + url);
        });
        if (!waitForApproval) {
            return kill.get() ? Decision.STOP : Decision.SEND;
        }
        try {
            while (!kill.get()) {
                Decision decision = decisions.poll(200, TimeUnit.MILLISECONDS);
                if (decision != null) {
                    return decision;
                }
            }
            return Decision.STOP;
        } finally {
            SwingUtilities.invokeLater(() -> {
                sendButton.setEnabled(false);
                skipButton.setEnabled(false);
            });
        }
    }

    @Override
    public boolean stopped() {
        return kill.get();
    }

    private void finished(int token) {
        if (token != runToken) {
            return;
        }
        running.set(false);
        kill.set(false);
        stopButton.setEnabled(false);
        sendButton.setEnabled(false);
        skipButton.setEnabled(false);
        approveEach.setEnabled(true);
        maxRequests.setEnabled(true);
        intervalSeconds.setEnabled(true);
    }

    private void choose(Decision decision) {
        sendButton.setEnabled(false);
        skipButton.setEnabled(false);
        decisions.offer(decision);
    }

    private void addRow(AuditEntry entry) {
        model.addRow(new Object[]{entry.time(), entry.result(), entry.method(), entry.url(), entry.status(), entry.reason()});
    }

    private void save() {
        try {
            api.persistence().extensionData().setString(AuditLog.KEY, AuditLog.toJson(entries));
        } catch (RuntimeException ex) {
            log.error("Ghostwire: could not save the Repeater audit log.");
        }
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("ghostwire-repeater-audit.txt"));
        if (chooser.showSaveDialog(component()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), AuditLog.exportText(List.copyOf(entries)));
            status.setText("Audit log saved.");
        } catch (IOException ex) {
            status.setText("Could not save the audit log.");
            log.error("Ghostwire: could not export the Repeater audit log.");
        }
    }

    private static int spinner(JSpinner spinner, int fallback) {
        try {
            spinner.commitEdit();
        } catch (ParseException ignored) {
            // Keep the last committed value.
        }
        Object value = spinner.getValue();
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static JTextArea plain(String text, int rows) {
        JTextArea area = new JTextArea(text);
        area.setRows(rows);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(null);
        return area;
    }
}
