package com.ghostwire.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import com.ghostwire.config.GhostwireConfig;
import com.ghostwire.config.Prompts;
import com.ghostwire.context.ContextBudgeter;
import com.ghostwire.context.TextBudget;
import com.ghostwire.features.IssueText;
import com.ghostwire.features.ReportText;
import com.ghostwire.features.TriageNotes;
import com.ghostwire.llm.BedrockConverseClient;
import com.ghostwire.llm.ChatMessage;
import com.ghostwire.llm.ClientLog;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lists in-scope scanner issues and drafts a triage report. */
public final class ReportView {

    private static final String BACKGROUND = "Ghostwire compared a scanner issue with the attached exchange and recommended a lower severity. The original issue is unchanged.";
    private static final String REMEDIATION_BACKGROUND = "Review the drafted remediation against the response before changing the original finding.";

    private final MontoyaApi api;
    private final GhostwireConfig config;
    private final Prompts prompts;
    private final BedrockConverseClient client;
    private final ContextBudgeter budgeter;
    private final ClientLog log;
    private final ExecutorService executor;
    private final Runnable showTab;

    private final List<AuditIssue> loaded = new ArrayList<>();
    private final List<Drafted> drafted = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Name", "Severity", "Confidence", "URL"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextArea report = new JTextArea();
    private final JTextArea status = plain("Load in-scope issues, or right-click issues and choose Ghostwire: Triage issues.", 3);
    private final JButton loadButton = new JButton("Load in-scope issues");
    private final JButton draftButton = new JButton("Draft report");
    private final JButton addButton = new JButton("Add downgraded issues");
    private final JButton exportButton = new JButton("Export report");
    private final JButton stopButton = new JButton("Stop");
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancel = new AtomicBoolean();

    private JComponent root;

    public ReportView(MontoyaApi api, GhostwireConfig config, Prompts prompts, BedrockConverseClient client,
                      ContextBudgeter budgeter, ClientLog log, ExecutorService executor, Runnable showTab) {
        this.api = api;
        this.config = config;
        this.prompts = prompts;
        this.client = client;
        this.budgeter = budgeter;
        this.log = log;
        this.executor = executor;
        this.showTab = showTab;
    }

    public JComponent component() {
        if (root != null) {
            return root;
        }
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        stopButton.setEnabled(false);
        addButton.setEnabled(false);
        loadButton.addActionListener(e -> loadFromSiteMap());
        draftButton.addActionListener(e -> draftSelected());
        addButton.addActionListener(e -> addDowngrades());
        exportButton.addActionListener(e -> export());
        stopButton.addActionListener(e -> cancel.set(true));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(loadButton);
        controls.add(draftButton);
        controls.add(addButton);
        controls.add(exportButton);
        controls.add(stopButton);

        JPanel north = new JPanel(new BorderLayout());
        north.add(plain("Load in-scope scanner issues, select the ones to review, and draft a report. "
                + "Right-click issues and choose Ghostwire: Triage issues to draft those directly. "
                + "At most " + ReportText.MAX_ISSUES + " issues are sent to the model in one pass. "
                + "The original issues stay as they are. Add downgraded issues writes a separate Ghostwire issue "
                + "only when the recommended severity is lower.", 4), BorderLayout.NORTH);
        north.add(controls, BorderLayout.CENTER);
        north.add(status, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(report));
        split.setResizeWeight(0.4);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        root = panel;
        return root;
    }

    public void loadFromSiteMap() {
        component();
        showTab.run();
        if (running.get()) {
            status.setText("A triage is already running.");
            return;
        }
        status.setText("Loading issues...");
        executor.submit(() -> {
            List<AuditIssue> issues;
            try {
                issues = api.siteMap().issues();
            } catch (RuntimeException ex) {
                setStatus("Could not read the site map.");
                return;
            }
            List<AuditIssue> inScope = inScope(issues);
            SwingUtilities.invokeLater(() -> {
                show(inScope);
                status.setText(inScope.isEmpty()
                        ? "No in-scope issues are on the site map."
                        : "Loaded " + inScope.size() + " in-scope issues. Select the ones to draft.");
            });
        });
    }

    public void triage(List<AuditIssue> issues) {
        component();
        showTab.run();
        if (!running.compareAndSet(false, true)) {
            status.setText("A triage is already running.");
            return;
        }
        List<AuditIssue> selected = issues == null ? List.of() : List.copyOf(issues);
        setBusy(true);
        executor.submit(() -> {
            try {
                List<AuditIssue> inScope = inScope(selected);
                SwingUtilities.invokeLater(() -> show(inScope));
                if (inScope.isEmpty()) {
                    setStatus("None of the selected issues are in scope.");
                    return;
                }
                runDraft(inScope);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    running.set(false);
                    setBusy(false);
                });
            }
        });
    }

    private void draftSelected() {
        if (!running.compareAndSet(false, true)) {
            status.setText("A triage is already running.");
            return;
        }
        int[] rows = table.getSelectedRows();
        List<AuditIssue> selected = new ArrayList<>();
        for (int row : rows) {
            if (row >= 0 && row < loaded.size()) {
                selected.add(loaded.get(row));
            }
        }
        if (selected.isEmpty()) {
            running.set(false);
            status.setText("Select one or more issues.");
            return;
        }
        setBusy(true);
        executor.submit(() -> {
            try {
                runDraft(selected);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    running.set(false);
                    setBusy(false);
                });
            }
        });
    }

    private void runDraft(List<AuditIssue> issues) {
        cancel.set(false);
        int limit = Math.min(issues.size(), ReportText.MAX_ISSUES);
        setStatus("Drafting " + limit + (issues.size() > limit ? " of " + issues.size() : "") + " issues.");
        List<Drafted> results = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) {
                break;
            }
            AuditIssue issue = issues.get(i);
            results.add(new Drafted(issue, review(issue)));
            setStatus("Drafted " + results.size() + " of " + limit + ".");
        }
        List<ReportText.Item> items = new ArrayList<>();
        for (Drafted result : results) {
            items.add(result.item);
        }
        String text = ReportText.render(items);
        boolean stopped = cancel.get();
        SwingUtilities.invokeLater(() -> {
            drafted.clear();
            drafted.addAll(results);
            report.setText(text);
            report.setCaretPosition(0);
            addButton.setEnabled(!results.isEmpty());
            status.setText(stopped ? "Stopped. The report has the issues drafted so far."
                    : "Drafted " + results.size() + " issues.");
        });
        log.info("Ghostwire: triage drafted " + results.size() + " issues.");
    }

    private ReportText.Item review(AuditIssue issue) {
        String name = issue.name() == null || issue.name().isBlank() ? "Untitled issue" : issue.name().trim();
        String url = issueUrl(issue);
        String severity = issue.severity() == null ? "INFORMATION" : issue.severity().name();
        String confidence = issue.confidence() == null ? "" : issue.confidence().name();
        try {
            String detail = TextBudget.headAndTail(plainDetail(issue.detail()), 4000);
            String exchange = exchange(issue);
            String user = TriageNotes.prompt(prompts.get(Prompts.REPORT), name, severity, confidence, url, detail, exchange);
            var result = client.converse(List.of(new ChatMessage("user", user)), prompts.systemPrompt(url));
            TriageNotes.Note note = TriageNotes.parse(result.text()).orElse(null);
            if (note == null) {
                return failed(name, url, severity, "The model reply was not a triage verdict.");
            }
            return new ReportText.Item(name, url, severity, note.verdict(), note.severity(), note.note(), note.repro(), note.remediation());
        } catch (Exception ex) {
            return failed(name, url, severity, client.safeMessage(ex));
        }
    }

    private void addDowngrades() {
        List<Drafted> snapshot = List.copyOf(drafted);
        if (snapshot.isEmpty()) {
            status.setText("Draft a report first.");
            return;
        }
        addButton.setEnabled(false);
        executor.submit(() -> {
            int added = 0;
            for (Drafted result : snapshot) {
                if (!ReportText.lowerSeverity(result.item.scannerSeverity(), result.item.severity(), result.item.verdict())) {
                    continue;
                }
                try {
                    api.siteMap().add(adjusted(result));
                    added++;
                } catch (RuntimeException ex) {
                    log.error("Ghostwire: could not add a downgraded issue. " + client.safeMessage(ex));
                }
            }
            int count = added;
            setStatus(count == 0
                    ? "No drafted issue had a lower severity, so nothing was added."
                    : "Added " + count + " downgraded Ghostwire issues. The original issues are unchanged.");
            SwingUtilities.invokeLater(() -> addButton.setEnabled(true));
        });
    }

    private AuditIssue adjusted(Drafted result) {
        AuditIssueSeverity severity = severity(result.item.severity());
        String url = result.item.url();
        List<HttpRequestResponse> messages = List.of();
        try {
            if (result.issue.requestResponses() != null) {
                messages = result.issue.requestResponses();
            }
        } catch (RuntimeException ex) {
            messages = List.of();
        }
        return AuditIssue.auditIssue(
                titled(result.item.name()),
                escape(result.item.note() + " " + result.item.repro()),
                escape(result.item.remediation()),
                url,
                severity,
                AuditIssueConfidence.TENTATIVE,
                BACKGROUND,
                REMEDIATION_BACKGROUND,
                severity,
                messages);
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("ghostwire-triage.txt"));
        if (chooser.showSaveDialog(component()) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), report.getText() == null ? "" : report.getText());
            status.setText("Report saved.");
        } catch (IOException ex) {
            status.setText("Could not save the report.");
            log.error("Ghostwire: could not export the triage report.");
        }
    }

    private void show(List<AuditIssue> issues) {
        loaded.clear();
        loaded.addAll(issues);
        model.setRowCount(0);
        for (AuditIssue issue : issues) {
            model.addRow(new Object[]{
                    issue.name() == null ? "" : issue.name(),
                    issue.severity() == null ? "" : issue.severity().name(),
                    issue.confidence() == null ? "" : issue.confidence().name(),
                    issueUrl(issue)
            });
        }
    }

    private List<AuditIssue> inScope(List<AuditIssue> issues) {
        List<AuditIssue> kept = new ArrayList<>();
        if (issues == null) {
            return kept;
        }
        for (AuditIssue issue : issues) {
            if (issue != null && inScope(issueUrl(issue))) {
                kept.add(issue);
            }
        }
        return kept;
    }

    private boolean inScope(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            return api.scope().isInScope(url);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String exchange(AuditIssue issue) {
        try {
            List<HttpRequestResponse> messages = issue.requestResponses();
            if (messages == null) {
                return "No request and response were attached.";
            }
            for (HttpRequestResponse message : messages) {
                if (message != null && message.request() != null) {
                    return budgeter.formatExchange(message.request(), message.response());
                }
            }
        } catch (RuntimeException ex) {
            return "The attached exchange could not be read.";
        }
        return "No request and response were attached.";
    }

    private String plainDetail(String detail) {
        String plain = IssueText.plain(detail);
        return budgeterRedact(plain);
    }

    private String budgeterRedact(String text) {
        return config.redactionEnabled() ? TextBudget.redactSecrets(text) : text;
    }

    private void setBusy(boolean busy) {
        loadButton.setEnabled(!busy);
        draftButton.setEnabled(!busy);
        exportButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        if (busy) {
            addButton.setEnabled(false);
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private static ReportText.Item failed(String name, String url, String severity, String message) {
        return new ReportText.Item(name, url, severity, "reject", "info", message, "not shown",
                "Review the cited exchange before changing the issue.");
    }

    private static String issueUrl(AuditIssue issue) {
        try {
            List<HttpRequestResponse> messages = issue.requestResponses();
            if (messages != null) {
                for (HttpRequestResponse message : messages) {
                    if (message != null && message.request() != null && message.request().url() != null
                            && !message.request().url().isBlank()) {
                        return message.request().url();
                    }
                }
            }
        } catch (RuntimeException ex) {
            // Fall through to the issue base URL.
        }
        String base = issue.baseUrl();
        return base == null ? "" : base;
    }

    private static AuditIssueSeverity severity(String value) {
        return switch (TriageNotes.canonical(value)) {
            case "high" -> AuditIssueSeverity.HIGH;
            case "medium" -> AuditIssueSeverity.MEDIUM;
            case "low" -> AuditIssueSeverity.LOW;
            default -> AuditIssueSeverity.INFORMATION;
        };
    }

    private static String titled(String name) {
        String text = name == null || name.isBlank() ? "Untitled issue" : name.trim();
        if (text.regionMatches(true, 0, "Ghostwire:", 0, "Ghostwire:".length())) {
            text = text.substring("Ghostwire:".length()).trim();
        }
        return "Ghostwire: " + text;
    }

    private static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    private record Drafted(AuditIssue issue, ReportText.Item item) {}
}
