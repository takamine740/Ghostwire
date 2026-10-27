package com.ghostwire.ui;

import burp.api.montoya.MontoyaApi;
import com.ghostwire.features.TechCatalog;
import com.ghostwire.features.TechHit;
import com.ghostwire.llm.ClientLog;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live inventory of technologies seen in in-scope traffic. */
public final class TechView {

    private final MontoyaApi api;
    private final ClientLog log;
    private final List<TechCatalog.Record> records = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Product", "Version", "Category", "Package URL", "Host", "Source", "Evidence", "URL", "First seen"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextField filter = new JTextField(24);
    private final JTextArea status = plain("No technologies yet.", 2);
    private JComponent root;

    public TechView(MontoyaApi api, ClientLog log) {
        this.api = api;
        this.log = log;
        records.addAll(TechCatalog.fromJson(api.persistence().extensionData().getString(TechCatalog.KEY)));
    }

    public JComponent component() {
        if (root != null) {
            return root;
        }
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });
        JButton export = new JButton("Export CSV");
        export.addActionListener(e -> export());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Filter"));
        controls.add(filter);
        controls.add(export);

        JPanel north = new JPanel(new BorderLayout());
        north.add(plain("Technologies seen in in-scope traffic from Proxy, Repeater, crawl, and active scan. "
                + "A script or stylesheet address in that page is recorded on the page host, including a file hosted elsewhere. "
                + "The version and package URL are filled in only when the page states the version. Nothing is sent to the model.", 4), BorderLayout.NORTH);
        north.add(controls, BorderLayout.CENTER);
        north.add(status, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        root = panel;
        refresh();
        return root;
    }

    public void observe(List<TechHit> hits, String host, String source, String url) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<TechCatalog.Record> changes = new ArrayList<>();
        String seen = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        synchronized (records) {
            for (TechHit hit : hits) {
                TechCatalog.Record changed = TechCatalog.merge(records, hit, host, source, url, seen);
                if (changed != null) {
                    changes.add(changed);
                }
            }
            if (!changes.isEmpty()) {
                save();
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        for (TechCatalog.Record change : changes) {
            String version = change.version().isBlank() ? "" : " " + change.version();
            log.info("Ghostwire: technology " + change.product() + version + " on " + change.host()
                    + (source == null || source.isBlank() ? "" : " (" + source + ")"));
        }
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<TechCatalog.Record> shown = new ArrayList<>();
        synchronized (records) {
            for (TechCatalog.Record record : records) {
                if (query.isEmpty() || matches(record, query)) {
                    shown.add(record);
                }
            }
        }
        model.setRowCount(0);
        for (TechCatalog.Record record : shown) {
            model.addRow(new Object[]{record.product(), record.version(), record.category(), record.purl(), record.host(),
                    record.source(), record.evidence(), record.url(), record.seen()});
        }
        status.setText(shown.size() + " shown, " + records.size() + " saved.");
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("ghostwire-technologies.csv"));
        if (chooser.showSaveDialog(component()) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<TechCatalog.Record> shown = new ArrayList<>();
        synchronized (records) {
            for (TechCatalog.Record record : records) {
                if (query.isEmpty() || matches(record, query)) {
                    shown.add(record);
                }
            }
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), TechCatalog.exportCsv(shown));
            status.setText("Technology list saved.");
        } catch (IOException ex) {
            status.setText("Could not save the technology list.");
            log.error("Ghostwire: could not export the technology list.");
        }
    }

    private void save() {
        try {
            api.persistence().extensionData().setString(TechCatalog.KEY, TechCatalog.toJson(records));
        } catch (RuntimeException ex) {
            log.error("Ghostwire: could not save the technology list.");
        }
    }

    private static boolean matches(TechCatalog.Record record, String query) {
        return contains(record.product(), query) || contains(record.version(), query) || contains(record.category(), query)
                || contains(record.purl(), query) || contains(record.host(), query) || contains(record.evidence(), query)
                || contains(record.url(), query) || contains(record.source(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
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
