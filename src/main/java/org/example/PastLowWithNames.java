package org.example;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.table.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class PastLowWithNames {

    // ===== CONFIG =====
    private static String API_KEY = ""; // set via key.getApiKey(...) below
    private static final String DATA_CSV = "/Users/baps/Documents/Twillo/SCFiles/output_365_days.csv";

    private static final int DAYS = 30;                // window size (NEWEST -> OLDEST): D0..D29 (from CSV)
    private static final int MAX_TICKERS = 200000;     // cap API pagination
    private static final boolean FETCH_LIVE_MARK = true;
    private static final int LIVE_MARK_LIMIT = 999999;    // avoid hammering the API
    private static final int LIVE_MARK_THROTTLE_MS = 60;

    private static JLabel rowCountLabel;

    // Model columns
    private static final int LOG_COL_SYMBOL       = 0;
    private static final int LOG_COL_DESC         = 1;
    private static final int LOG_COL_PCT          = 2;
    private static final int LOG_COL_AVGVOL       = 3;
    private static final int LOG_COL_NEWLOW       = 4;
    private static final int LOG_COL_VOLSPIKE     = 5;
    private static final int LOG_COL_MARKPRICE    = 6;
    private static final int LOG_COL_RANGEBAR     = 7;
    private static final int LOG_COL_TODAY        = 8;
    private static final int LOG_FIRST_DATE_COL   = 9;

    // RIGHT table relative indexes
    private static final int R_PCT       = 0;  // model 2
    private static final int R_AVGVOL    = 1;  // 3
    private static final int R_NEWLOW    = 2;  // 4
    private static final int R_VOLSPIKE  = 3;  // 5
    private static final int R_MARKPRICE = 4;  // 6
    private static final int R_RANGEBAR  = 5;  // 7
    private static final int R_TODAY     = 6;  // 8
    private static final int R_DATES0    = 7;  // 9..

    // ===== UI =====
    private static JFrame frame;
    private static JTable leftTable;
    private static JTable rightTable;
    private static DefaultTableModel fullModel;
    private static TableRowSorter<TableModel> sharedSorter;
    private static JScrollPane leftScroll;
    private static JScrollPane rightScroll;

    // Controls
    private static JCheckBox sp500Checkbox;
    private static JCheckBox earningsCheckbox;
    private static JCheckBox myIndexCheckbox;

    private static JRadioButton morningButton;
    private static JRadioButton eveningButton;
    private static ButtonGroup earningsTimeGroup;

    private static JTextField symbolFilterField;
    private static JTextField fromMarkAmountField;
    private static JTextField toMarkAmountField;

    private static JButton displayButton;
    private static JProgressBar progress;

    // Display mode (Amount / Diff / Both)
    private static JRadioButton showAmountRb;
    private static JRadioButton showDiffRb;
    private static JRadioButton showBothRb;
    private static ButtonGroup displayModeGroup;
    private enum DisplayMode { AMOUNT, DIFF, BOTH }
    private static volatile DisplayMode displayMode = DisplayMode.BOTH;

    public static void main(String[] args) {
        API_KEY = key.getApiKey("polygon.apiKey");
        SwingUtilities.invokeLater(PastLowWithNames::buildUI);
    }

    // ========== Build UI ==========
    private static void buildUI() {
        frame = new JFrame("Past 30 Days Report (click Display to run)");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8,8));

        // Controls
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        JPanel symbolPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        symbolPanel.add(new JLabel("Filter Symbol:"));
        symbolFilterField = new JTextField(12);
        symbolPanel.add(symbolFilterField);

        JPanel markPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        markPanel.add(new JLabel("Mark From:"));
        fromMarkAmountField = new JTextField(8);
        ((AbstractDocument)fromMarkAmountField.getDocument()).setDocumentFilter(new NumericFilter());
        markPanel.add(fromMarkAmountField);
        markPanel.add(new JLabel("To:"));
        toMarkAmountField = new JTextField(8);
        ((AbstractDocument)toMarkAmountField.getDocument()).setDocumentFilter(new NumericFilter());
        markPanel.add(toMarkAmountField);

        JPanel checkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sp500Checkbox = new JCheckBox("S&P 500");
        earningsCheckbox = new JCheckBox("Earnings");
        myIndexCheckbox = new JCheckBox("My Index");
        checkPanel.add(sp500Checkbox);
        checkPanel.add(earningsCheckbox);
        checkPanel.add(myIndexCheckbox);

        JPanel earningsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        earningsPanel.add(new JLabel("Earnings Time:"));
        morningButton = new JRadioButton("Morning");
        eveningButton = new JRadioButton("Evening");
        earningsTimeGroup = new ButtonGroup();
        earningsTimeGroup.add(morningButton);
        earningsTimeGroup.add(eveningButton);
        morningButton.setEnabled(false);
        eveningButton.setEnabled(false);
        earningsPanel.add(morningButton);
        earningsPanel.add(eveningButton);
        earningsCheckbox.addItemListener(e -> {
            boolean on = (e.getStateChange() == ItemEvent.SELECTED);
            morningButton.setEnabled(on);
            eveningButton.setEnabled(on);
            if (!on) earningsTimeGroup.clearSelection();
        });

        // Display mode radios
        JPanel displayModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        displayModePanel.add(new JLabel("Show:"));
        showAmountRb = new JRadioButton("Amount");
        showDiffRb   = new JRadioButton("Difference");
        showBothRb   = new JRadioButton("Both", true);
        displayModeGroup = new ButtonGroup();
        displayModeGroup.add(showAmountRb);
        displayModeGroup.add(showDiffRb);
        displayModeGroup.add(showBothRb);
        ItemListener modeListener = e -> {
            if (e.getStateChange() != ItemEvent.SELECTED) return;
            if (showAmountRb.isSelected()) displayMode = DisplayMode.AMOUNT;
            else if (showDiffRb.isSelected()) displayMode = DisplayMode.DIFF;
            else displayMode = DisplayMode.BOTH;
            installSharedSorter();
            adjustAllRightColumnWidthsToContent();
            ensureSelectTopRow(); // keep focus on row 0 after mode-triggered resort
            rightTable.repaint();
        };
        showAmountRb.addItemListener(modeListener);
        showDiffRb.addItemListener(modeListener);
        showBothRb.addItemListener(modeListener);
        displayModePanel.add(showAmountRb);
        displayModePanel.add(showDiffRb);
        displayModePanel.add(showBothRb);

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        displayButton = new JButton("Display");
        progress = new JProgressBar(0, 100);
        progress.setPreferredSize(new Dimension(260, 18));
        progress.setStringPainted(true);
        progress.setForeground(new Color(0, 122, 255)); // blue
        progress.setVisible(false);
        rowCountLabel = new JLabel("Count: 0");
        rowCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        actionPanel.add(displayButton);
        actionPanel.add(progress);
        actionPanel.add(rowCountLabel);

        controls.add(symbolPanel);
        controls.add(markPanel);
        controls.add(checkPanel);
        controls.add(earningsPanel);
        controls.add(displayModePanel);
        controls.add(actionPanel);


        // Tables placeholder — swap models after run
        fullModel = new DefaultTableModel();
        leftTable  = new JTable(fullModel);
        rightTable = new JTable(fullModel);

        // Row selection only (no cell/column selection)
        leftTable.setRowSelectionAllowed(true);
        rightTable.setRowSelectionAllowed(true);
        leftTable.setColumnSelectionAllowed(false);
        rightTable.setColumnSelectionAllowed(false);
        leftTable.setCellSelectionEnabled(false);
        rightTable.setCellSelectionEnabled(false);

        // Force row selection on mouse down in either table
        MouseAdapter rowSelector = makeRowSelector();
        leftTable.addMouseListener(rowSelector);
        rightTable.addMouseListener(rowSelector);

        // LEFT (frozen)
        leftTable.setAutoCreateRowSorter(false);
        leftTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        leftTable.setShowVerticalLines(true);
        leftTable.setShowHorizontalLines(true);
        leftTable.setGridColor(new Color(230,230,230));
        leftTable.setRowHeight(22);
        leftTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // RIGHT (scrollable)
        rightTable.setAutoCreateRowSorter(false);
        rightTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rightTable.setShowVerticalLines(true);
        rightTable.setShowHorizontalLines(true);
        rightTable.setGridColor(new Color(230,230,230));
        rightTable.setRowHeight(22);
        rightTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Transparent selection (we draw overlay in renderer)
        Color transparent = new Color(0,0,0,0);
        leftTable.setSelectionBackground(transparent);
        leftTable.setSelectionForeground(Color.BLACK);
        rightTable.setSelectionBackground(transparent);
        rightTable.setSelectionForeground(Color.BLACK);

        // Scroll panes + sync vertical scroll
        leftScroll = new JScrollPane(leftTable);
        rightScroll = new JScrollPane(rightTable);
        leftScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rightScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        syncVerticalScroll(leftScroll, rightScroll);

        // Layout: frozen left + right
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setDividerSize(6);
        split.setResizeWeight(0);
        split.setDividerLocation(350);

        displayButton.addActionListener(ev -> runReportAsync());

        frame.add(controls, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.setSize(1900, 1000);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static MouseAdapter makeRowSelector() {
        return new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                JTable t = (JTable) e.getSource();
                int row = t.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    t.requestFocusInWindow();
                    t.getSelectionModel().setSelectionInterval(row, row);
                }
            }
        };
    }

    private static void syncVerticalScroll(JScrollPane left, JScrollPane right) {
        left.getVerticalScrollBar().setModel(right.getVerticalScrollBar().getModel());
    }

    // After models/column models are set, share selection + link both panes
    private static void applySharedSelectionModel() {
        DefaultListSelectionModel shared = new DefaultListSelectionModel();
        shared.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        leftTable.setSelectionModel(shared);
        rightTable.setSelectionModel(shared);

        ListSelectionListener linker = new ListSelectionListener() {
            private boolean inUpdate = false;
            @Override public void valueChanged(ListSelectionEvent e) {
                if (inUpdate || e.getValueIsAdjusting()) return;
                inUpdate = true;
                try {
                    int viewRowLeft  = leftTable.getSelectedRow();
                    int viewRowRight = rightTable.getSelectedRow();
                    int viewRow = Math.max(viewRowLeft, viewRowRight);
                    if (viewRow >= 0) {
                        leftTable.scrollRectToVisible(leftTable.getCellRect(viewRow, 0, true));
                        rightTable.scrollRectToVisible(rightTable.getCellRect(viewRow, 0, true));
                    }
                } finally { inUpdate = false; }
            }
        };
        leftTable.getSelectionModel().addListSelectionListener(linker);
        rightTable.getSelectionModel().addListSelectionListener(linker);
    }

    // Make row 0 selected and scrolled into view (both panes)
    private static void ensureSelectTopRow() {
        if (fullModel == null || fullModel.getRowCount() == 0) return;
        int top = 0;
        leftTable.getSelectionModel().setSelectionInterval(top, top);
        rightTable.getSelectionModel().setSelectionInterval(top, top);
        leftTable.scrollRectToVisible(leftTable.getCellRect(top, 0, true));
        rightTable.scrollRectToVisible(rightTable.getCellRect(top, 0, true));
    }

    // ========== Run report ==========
    private static void runReportAsync() {
        displayButton.setEnabled(false);
        progress.setVisible(true);
        progress.setValue(0);
        progress.setString("Loading...");

        new SwingWorker<Void, Integer>() {
            List<String> header;
            List<String[]> fullRows;
            int totalSymbols;

            @Override
            protected Void doInBackground() {
                try {
                    if (API_KEY == null || API_KEY.isBlank())
                        throw new IllegalStateException("Polygon API key not set.");

                    // 1) Read CSV & group
                    Map<String, List<String[]>> grouped = readAndGroup(DATA_CSV);
                    Set<String> symbolsInFile = new LinkedHashSet<>(grouped.keySet());

                    // Filter by symbol text box
                    String symFilter = symbolFilterField.getText().trim();
                    if (!symFilter.isEmpty()) {
                        String s = symFilter.toLowerCase(Locale.ROOT);
                        symbolsInFile.removeIf(t -> !t.toLowerCase(Locale.ROOT).contains(s));
                    }

                    totalSymbols = symbolsInFile.size();
                    if (totalSymbols == 0) throw new IllegalStateException("No symbols after filtering.");

                    // 2) Names only for needed symbols
                    Map<String, String> tickerNameMap = fetchSymbolsFor(symbolsInFile);

                    // 3) Global date window NEWEST->OLDEST
                    List<LocalDate> win = pickGlobalDateWindowNewest(grouped, DAYS);
                    if (win.isEmpty()) throw new IllegalStateException("No symbol had at least " + DAYS + " rows.");
                    LocalDate windowNewest = win.get(0);
                    LocalDate windowOldest = win.get(win.size() - 1);
                    DateTimeFormatter hdrFmt = DateTimeFormatter.ofPattern("MM/dd/yy");

                    // 4) Build rows
                    fullRows = new ArrayList<>();
                    int liveCount = 0;

                    // Mark amount filter range
                    double fromMark = parseDoubleSafe(fromMarkAmountField.getText().trim());
                    double toMark   = parseDoubleSafe(toMarkAmountField.getText().trim());
                    boolean hasFrom = !Double.isNaN(fromMark);
                    boolean hasTo   = !Double.isNaN(toMark);

                    // get snapshot

                    HashMap<String, String> tickersMarket = fetchsnapshot(API_KEY);


                    int processed = 0;

                    for (String symbol : symbolsInFile) {
                        processed++;
                        publish((int) (processed * 100.0 / totalSymbols));

                        List<String[]> rowsPerSymbol = grouped.get(symbol);
                        if (rowsPerSymbol == null) continue;

                        Map<LocalDate, String[]> byDate = new HashMap<>();
                        for (String[] r : rowsPerSymbol) byDate.put(parseDateSafe(r[0]), r);

                        // Align to win (NEWEST->OLDEST)
                        List<String[]> aligned = new ArrayList<>(DAYS);
                        boolean missing = false;
                        for (LocalDate d : win) {
                            String[] r = byDate.get(d);
                            if (r == null) { missing = true; break; }
                            aligned.add(r);
                        }
                        if (missing) continue;

                        String desc = tickerNameMap.getOrDefault(symbol, symbol);
                        if (Objects.equals(desc, symbol)){
                            // If name wasn't found (filtering by S&P/MyIndex may exclude it), skip
                            continue;
                        }
                        String[] fullRow = buildFullRowNewestToOldest(symbol, desc, aligned);

                        // Live MarkPrice → Today
                        if (FETCH_LIVE_MARK && liveCount < LIVE_MARK_LIMIT) {
                            //  Double live = fetchLiveMarkPrice(symbol);
                                Double live =  Double.parseDouble(tickersMarket.get(symbol));
                            if (live != null) {
                                fullRow[LOG_COL_MARKPRICE] = fmt2(live);
                                double d0 = dbl(aligned.get(0)[4]); // newest close (D0)
                                recomputeTodayFromMark(fullRow, d0);
                            }
                            liveCount++;
                          //  try { Thread.sleep(LIVE_MARK_THROTTLE_MS); } catch (InterruptedException ignore) {}
                        }

                        // Mark filter
                        if (hasFrom || hasTo) {
                            double mark = parseDoubleSafe(fullRow[LOG_COL_MARKPRICE]);
                            if (!Double.isNaN(mark)) {
                                if (hasFrom && mark < fromMark) continue;
                                if (hasTo   && mark > toMark)   continue;
                            }
                        }

                        fullRows.add(fullRow);
                    }

                    // 5) Header
                    header = new ArrayList<>(Arrays.asList(
                            "Symbol","Description","PctChange","AvgVol","NewLow?","VolSpike?","MarkPrice","RangeBar"
                    ));
                    String todayStr = LocalDate.now(ZoneId.systemDefault()).format(hdrFmt);
                    header.add(todayStr + " (Today)");
                    for (LocalDate d : win) header.add(hdrFmt.format(d));

                    // 6) Save CSV
                    Path out = Paths.get("past_30_days_with_today.csv");
                    writeCsv(out, header, fullRows, windowNewest, windowOldest);

                } catch (Exception ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                }
                return null;
            }

            @Override protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int v = chunks.get(chunks.size()-1);
                    progress.setIndeterminate(false);
                    progress.setValue(v);
                    progress.setString(v + "%");
                }
            }

            @Override protected void done() {
                // Build fullModel
                Vector<String> cols = new Vector<>(header);
                Vector<Vector<String>> data = new Vector<>();
                for (String[] r : fullRows) data.add(new Vector<>(Arrays.asList(r)));
                fullModel = new DefaultTableModel(data, cols) {
                    @Override public boolean isCellEditable(int r, int c) { return false; }
                };

                // Apply to both tables
                leftTable.setModel(fullModel);
                rightTable.setModel(fullModel);

                // Column models
                leftTable.setColumnModel(buildLeftColumnModel());
                rightTable.setColumnModel(buildRightColumnModel());

                // Shared selection model
                applySharedSelectionModel();

                // Renderers on RIGHT
                AmountDiffRenderer amountDiff = new AmountDiffRenderer();
                YesGreenStickyRenderer yesSticky = new YesGreenStickyRenderer();
                TransparentSelectionPlainRenderer plain = new TransparentSelectionPlainRenderer();
                RangeBarRenderer rangeBar = new RangeBarRenderer();

                setRenderer(rightTable, R_PCT, plain);
                setRenderer(rightTable, R_AVGVOL, plain);
                setRenderer(rightTable, R_NEWLOW, yesSticky);
                setRenderer(rightTable, R_VOLSPIKE, yesSticky);
                setRenderer(rightTable, R_MARKPRICE, plain);
                setRenderer(rightTable, R_RANGEBAR, rangeBar);
                setRenderer(rightTable, R_TODAY, new TodayFromMarkRenderer());
                for (int c = R_DATES0; c < rightTable.getColumnCount(); c++) {
                    setRenderer(rightTable, c, amountDiff);
                }

                // LEFT renderers
                DefaultTableCellRenderer leftPlain = new TransparentSelectionPlainRenderer();
                setRenderer(leftTable, 0, leftPlain);
                setRenderer(leftTable, 1, leftPlain);

                // Widths
                setColWidth(leftTable, 0, 110);
                setColWidth(leftTable, 1, 280);
                setColWidth(rightTable, R_PCT, 90);
                setColWidth(rightTable, R_AVGVOL, 90);
                setColWidth(rightTable, R_NEWLOW, 80);
                setColWidth(rightTable, R_VOLSPIKE, 80);
                setColWidth(rightTable, R_MARKPRICE, 100);
                setColWidth(rightTable, R_RANGEBAR, 260);

                // Shared sorter
                installSharedSorter();

                // Ensure sort selection goes to row 0
                addTopRowSelectionOnSort(sharedSorter);

                // Fit Today + dates
                adjustAllRightColumnWidthsToContent();

                // Select top by default
                ensureSelectTopRow();

                // Update count label
                if (rowCountLabel != null) {
                    rowCountLabel.setText("Count: " + fullModel.getRowCount());
                }

                progress.setVisible(false);
                displayButton.setEnabled(true);
            }
        }.execute();
    }

    // Today mirrors MarkPrice and shows ± vs D0
    private static void recomputeTodayFromMark(String[] row, double d0) {
        double mark = parseDoubleSafe(row[LOG_COL_MARKPRICE]);
        String amt   = Double.isNaN(mark) ? "" : fmt2(mark);
        String diff  = (Double.isNaN(mark) || Double.isNaN(d0)) ? "—"
                : String.format(Locale.US, "%+.2f", (mark - d0));
        row[LOG_COL_TODAY] = amt + " (" + diff + ")";
    }

    // Left ColumnModel (Symbol, Description)
    private static TableColumnModel buildLeftColumnModel() {
        DefaultTableColumnModel cm = new DefaultTableColumnModel();
        cm.addColumn(cloneColumnFromModel(0, "Symbol", 110));
        cm.addColumn(cloneColumnFromModel(1, "Description", 280));
        return cm;
    }

    // Right ColumnModel (PctChange..dates)
    private static TableColumnModel buildRightColumnModel() {
        DefaultTableColumnModel cm = new DefaultTableColumnModel();
        int[] modelCols = buildRightModelColumnOrder();
        String[] names  = buildRightColumnNames();
        for (int i = 0; i < modelCols.length; i++) {
            cm.addColumn(cloneColumnFromModel(modelCols[i], names[i], 100));
        }
        return cm;
    }

    private static int[] buildRightModelColumnOrder() {
        int totalCols = fullModel.getColumnCount();
        int[] arr = new int[totalCols - 2];
        int idx = 0;
        for (int mc = 2; mc < totalCols; mc++) arr[idx++] = mc;
        return arr;
    }

    private static String[] buildRightColumnNames() {
        int totalCols = fullModel.getColumnCount();
        String[] names = new String[totalCols - 2];
        int idx = 0;
        for (int mc = 2; mc < totalCols; mc++) names[idx++] = fullModel.getColumnName(mc);
        return names;
    }

    private static TableColumn cloneColumnFromModel(int modelIndex, String header, int prefWidth) {
        TableColumn tc = new TableColumn(modelIndex);
        tc.setHeaderValue(header);
        tc.setPreferredWidth(prefWidth);
        return tc;
    }

    // Shared sorter on BOTH tables
    private static void installSharedSorter() {
        sharedSorter = new TableRowSorter<>(fullModel);

        sharedSorter.setSortable(LOG_COL_SYMBOL, true);
        sharedSorter.setSortable(LOG_COL_DESC, true);

        sharedSorter.setComparator(LOG_COL_PCT, (a, b) -> Double.compare(parsePercent(a), parsePercent(b)));
        sharedSorter.setComparator(LOG_COL_AVGVOL, (a, b) -> Long.compare(parseLong(a), parseLong(b)));
        sharedSorter.setComparator(LOG_COL_NEWLOW, (a, b) -> Boolean.compare(isYes(a), isYes(b)));
        sharedSorter.setComparator(LOG_COL_VOLSPIKE, (a, b) -> Boolean.compare(isYes(a), isYes(b)));
        sharedSorter.setComparator(LOG_COL_MARKPRICE, (a, b) -> Double.compare(parseDouble(a), parseDouble(b)));
        sharedSorter.setSortable(LOG_COL_RANGEBAR, false);

        Comparator<Object> amtOrDiffComparator = (a, b) -> {
            double va = parseAmountOrDiff(a, displayMode);
            double vb = parseAmountOrDiff(b, displayMode);
            return Double.compare(va, vb);
        };
        sharedSorter.setComparator(LOG_COL_TODAY, amtOrDiffComparator);
        for (int c = LOG_FIRST_DATE_COL; c < fullModel.getColumnCount(); c++) {
            sharedSorter.setComparator(c, amtOrDiffComparator);
        }

        leftTable.setRowSorter(sharedSorter);
        rightTable.setRowSorter(sharedSorter);
    }

    // After any sort movement, select row 0 and scroll to top
    private static void addTopRowSelectionOnSort(RowSorter<? extends TableModel> sorter) {
        if (sorter == null) return;
        sorter.addRowSorterListener(new RowSorterListener() {
            @Override public void sorterChanged(RowSorterEvent e) {
                // Only after sort model changed / new sort keys applied
                if (e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED ||
                        e.getType() == RowSorterEvent.Type.SORTED) {
                    SwingUtilities.invokeLater(PastLowWithNames::ensureSelectTopRow);
                }
            }
        });
    }

    // Fit Today + date columns to content
    private static void adjustAllRightColumnWidthsToContent() {
        if (rightTable == null) return;
        autoFitColumn(rightTable, R_TODAY, 40, 240, 200);
        for (int c = R_DATES0; c < rightTable.getColumnCount(); c++) {
            autoFitColumn(rightTable, c, 40, 240, 200);
        }
        JTableHeader hdr = rightTable.getTableHeader();
        if (hdr != null) hdr.repaint();
    }

    private static void autoFitColumn(JTable table, int col, int minWidth, int maxWidth, int maxRowsToScan) {
        if (col < 0 || col >= table.getColumnCount()) return;
        TableColumnModel colModel = table.getColumnModel();
        TableColumn tc = colModel.getColumn(col);

        Component comp;
        int width = 0;

        TableCellRenderer headerRenderer = table.getTableHeader() != null ? table.getTableHeader().getDefaultRenderer() : null;
        comp = (headerRenderer != null)
                ? headerRenderer.getTableCellRendererComponent(table, tc.getHeaderValue(), false, false, -1, col)
                : new JLabel(String.valueOf(tc.getHeaderValue()));
        width = Math.max(width, comp.getPreferredSize().width);

        int rows = Math.min(table.getRowCount(), Math.max(50, maxRowsToScan));
        for (int row = 0; row < rows; row++) {
            TableCellRenderer r = table.getCellRenderer(row, col);
            comp = table.prepareRenderer(r, row, col);
            width = Math.max(width, comp.getPreferredSize().width);
        }

        width += 12;
        width = Math.max(minWidth, Math.min(width, maxWidth));
        tc.setPreferredWidth(width);
        tc.setMinWidth(Math.min(width, 60));
    }

    private static void setRenderer(JTable t, int col, DefaultTableCellRenderer r) {
        if (col >= 0 && col < t.getColumnCount())
            t.getColumnModel().getColumn(col).setCellRenderer(r);
    }

    private static void setColWidth(JTable t, int col, int width) {
        if (col >= 0 && col < t.getColumnCount()) {
            t.getColumnModel().getColumn(col).setPreferredWidth(width);
            t.getColumnModel().getColumn(col).setMinWidth(Math.min(width, 60));
        }
    }

    // ---------- Build row: Today + CSV columns with amount (+/-diff) ----------
    private static String[] buildFullRowNewestToOldest(String symbol, String desc, List<String[]> newestToOldest) {
        double newestClose = dbl(newestToOldest.get(0)[4]);
        double newestLow   = dbl(newestToOldest.get(0)[6]);
        double newestHigh  = dbl(newestToOldest.get(0)[5]);

        double oldestClose = dbl(newestToOldest.get(newestToOldest.size()-1)[4]);
        double pctChange   = (oldestClose == 0) ? 0.0 : ((newestClose - oldestClose) / oldestClose) * 100.0;

        long volSum = 0;
        for (String[] r : newestToOldest) volSum += lng(r[2]);
        long avgVol = Math.round((double) volSum / newestToOldest.size());

        double minLow = Double.POSITIVE_INFINITY;
        for (String[] r : newestToOldest) {
            double lo = dbl(r[6]);
            if (!Double.isNaN(lo) && lo < minLow) minLow = lo;
        }
        boolean newLow = dbl(newestToOldest.get(0)[6]) <= minLow + 1e-9;
        boolean volSpike = avgVol > 0 && lng(newestToOldest.get(0)[2]) >= 2L * avgVol;

        String markPriceStr = fmt2(newestClose); // will be overwritten by live
        String rangeBar     = buildRangeBar(newestLow, newestHigh, newestClose);

        String[] row = new String[8 + 1 + newestToOldest.size()];
        row[LOG_COL_SYMBOL]     = symbol;
        row[LOG_COL_DESC]       = desc;
        row[LOG_COL_PCT]        = String.format(Locale.US, "%.2f%%", pctChange);
        row[LOG_COL_AVGVOL]     = String.valueOf(avgVol);
        row[LOG_COL_NEWLOW]     = newLow ? "Yes" : "No";
        row[LOG_COL_VOLSPIKE]   = volSpike ? "Yes" : "No";
        row[LOG_COL_MARKPRICE]  = markPriceStr;
        row[LOG_COL_RANGEBAR]   = rangeBar;

        // Today computed from MarkPrice vs D0
        recomputeTodayFromMark(row, newestClose);

        // CSV columns (newest->older): amount (+/-diff vs next older)
        for (int i = 0; i < newestToOldest.size(); i++) {
            double amt = dbl(newestToOldest.get(i)[4]);
            String base = fmt2(amt);
            String diffStr;
            if (i < newestToOldest.size() - 1) {
                double nextOlder = dbl(newestToOldest.get(i+1)[4]);
                diffStr = (!Double.isNaN(amt) && !Double.isNaN(nextOlder))
                        ? String.format(Locale.US, "%+.2f", (amt - nextOlder))
                        : "—";
            } else {
                diffStr = "—";
            }
            row[LOG_FIRST_DATE_COL + i] = base + " (" + diffStr + ")";
        }
        return row;
    }

    // ---------- Range bar ----------
    private static String buildRangeBar(double low, double high, double last) {
        if (Double.isNaN(low) || Double.isNaN(high) || high <= low || Double.isNaN(last)) return "";
        int barLength = 20;
        double posRatio = (last - low) / (high - low);
        posRatio = Math.max(0, Math.min(1, posRatio));
        int pos = (int) Math.round(posRatio * (barLength - 1));

        String lowStr  = String.format(Locale.US, "%7.2f", low);
        String highStr = String.format(Locale.US, "%7.2f", high);

        StringBuilder sb = new StringBuilder(lowStr).append(" ").append("|");
        for (int i = 0; i < barLength; i++) sb.append(i == pos ? "V" : "-");
        sb.append("| ").append(highStr);
        return sb.toString();
    }

    // ---------- CSV ----------
    private static Map<String, List<String[]>> readAndGroup(String filePath) throws IOException {
        Map<String, List<String[]>> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 7) continue;
                String sym = parts[1].trim();
                if (sym.isEmpty()) continue;
                map.computeIfAbsent(sym, k -> new ArrayList<>()).add(parts);
            }
        }
        return map;
    }

    // ---------- Window: NEWEST -> OLDEST ----------
    private static List<LocalDate> pickGlobalDateWindowNewest(Map<String, List<String[]>> grouped, int days) {
        TreeSet<LocalDate> last = new TreeSet<>();
        for (List<String[]> rows : grouped.values()) {
            for (String[] r : rows) {
                LocalDate d = parseDateSafe(r[0]);
                last.add(d);
                if (last.size() > days) last.pollFirst();
            }
        }
        if (last.size() < days) return Collections.emptyList();
        ArrayList<LocalDate> oldestToNewest = new ArrayList<>(last);
        Collections.reverse(oldestToNewest); // NEWEST -> OLDEST
        return oldestToNewest;
    }

    // ---------- Names for only needed symbols ----------
    private static Map<String, String> fetchSymbolsFor(Set<String> needed) throws IOException {
//        Map<String, String> map = new LinkedHashMap<>();
//        if (needed.isEmpty()) return map;
//
//        String nextUrl = "https://api.polygon.io/v3/reference/tickers?market=stocks&active=true&limit=1000&apiKey=" + API_KEY;
//        while (nextUrl != null && map.size() < needed.size() && map.size() < MAX_TICKERS) {
//            JSONObject obj = httpGetJson(nextUrl);
//            JSONArray results = obj.optJSONArray("results");
//            if (results != null) {
//                for (int i = 0; i < results.length() && map.size() < needed.size() && map.size() < MAX_TICKERS; i++) {
//                    JSONObject t = results.getJSONObject(i);
//                    String ticker = t.optString("ticker", "").trim();
//                    if (!needed.contains(ticker)) continue;
//                    String name = t.optString("name", "").trim();
//                    if (!ticker.isEmpty() && !name.isEmpty()) map.put(ticker, name);
//                }
//            }
//            String nextToken = obj.optString("next_url", null);
//            nextUrl = (nextToken != null && !nextToken.isEmpty()) ? nextToken + "&apiKey=" + API_KEY : null;
//        }
//        return map;
        boolean filterMyIndex = myIndexCheckbox.isSelected();
        boolean filterSP500 = sp500Checkbox.isSelected();

        Map<String, String> myIndexCompanies = MyIndex.MyIndexCompanies();
        Map<String, String> companies = Index.IndexCompanies();

        Map<String, String> map = new LinkedHashMap<>();
        if (needed.isEmpty()) return map;
        System.out.println("fetchSymbolsFor");
        String nextUrl = "https://api.polygon.io/v3/reference/tickers?market=stocks&active=true&limit=1000&apiKey=" + API_KEY;
        while (nextUrl != null && map.size() < needed.size() && map.size() < MAX_TICKERS) {
            JSONObject obj = httpGetJson(nextUrl);
            JSONArray results = obj.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length() && map.size() < needed.size() && map.size() < MAX_TICKERS; i++) {
                    JSONObject t = results.getJSONObject(i);
                    String ticker = t.optString("ticker", "").trim();
                    if (!needed.contains(ticker)) continue;

                    if (filterMyIndex && myIndexCompanies.get(ticker) == null) continue;
                    if (filterSP500  && companies.get(ticker) == null) continue;

                    String name = t.optString("name", "").trim();
                    if (!ticker.isEmpty() && !name.isEmpty()) map.put(ticker, name);
                }
            }
            String nextToken = obj.optString("next_url", null);
            nextUrl = (nextToken != null && !nextToken.isEmpty()) ? nextToken + "&apiKey=" + API_KEY : null;
        }
        return map;
    }

    // ---------- Live MarkPrice ----------
    private static Double fetchLiveMarkPrice(String symbol) {
        try {
            String url = "https://api.polygon.io/v2/last/trade/" + symbol + "?apiKey=" + API_KEY;
            JSONObject obj = httpGetJson(url);
            JSONObject trade = obj.optJSONObject("results");
            if (trade != null) {
                double p = trade.optDouble("p", Double.NaN);
                if (!Double.isNaN(p)) return p;
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ---------- HTTP ----------
    private static JSONObject httpGetJson(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    // ---------- Renderers ----------
    // ✅ Uses row selection model so the WHOLE ROW highlights; no left stripe now.
    static abstract class OverlayStripeRenderer extends DefaultTableCellRenderer {
        private boolean overlaySelected = false;
        OverlayStripeRenderer() { setOpaque(true); }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

            // Don't let default blue selection override our colors
            super.getTableCellRendererComponent(table, value, /*isSelected*/false, hasFocus, row, column);

            // Ask the table if the ROW is selected (not just this cell)
            overlaySelected = table.getSelectionModel().isSelectedIndex(row);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!overlaySelected) return;

            Graphics2D g2 = (Graphics2D) g.create();
            // Semi‑transparent gray veil (no stripe, no border)
            g2.setComposite(AlphaComposite.SrcOver.derive(0.40f));
            g2.setColor(new Color(200,200,200));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    static class BracketDiffRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Color bg = Color.WHITE;
            Color fg = Color.BLACK;
            double diff = extractDiff(value);
            if (!Double.isNaN(diff)) {
                if (diff > 0) bg = new Color(205, 245, 205);
                else if (diff < 0) bg = new Color(255, 210, 210);
            }
            setBackground(bg);
            setForeground(fg);
            return this;
        }

        protected double extractDiff(Object value) {
            if (value == null) return Double.NaN;
            String s = value.toString().trim(); // "370.50 (+4.50)"
            int lb = s.indexOf('('), rb = s.indexOf(')');
            if (lb < 0 || rb <= lb+1) return Double.NaN;
            String inside = s.substring(lb+1, rb).trim(); // "+4.50" or "—"
            if (inside.equals("—")) return Double.NaN;
            try { return Double.parseDouble(inside.replace(",", "")); }
            catch (Exception e) { return Double.NaN; }
        }
    }

    static class AmountDiffRenderer extends BracketDiffRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String raw = (value == null) ? "" : value.toString();
            String amount = raw, diff = null;
            int lb = raw.indexOf('('), rb = raw.indexOf(')');
            if (lb >= 0 && rb > lb) {
                amount = raw.substring(0, lb).trim();
                diff   = raw.substring(lb + 1, rb).trim(); // e.g., +4.50
            }

            String toShow;
            switch (displayMode) {
                case AMOUNT: toShow = (amount.isEmpty() ? raw : amount); break;
                case DIFF:   toShow = (diff == null || diff.isEmpty()) ? "—" : diff; break;
                default:     toShow = raw; break;
            }
            setText(toShow);
            return this;
        }
    }

    // TODAY renderer: always reads MarkPrice from model and compares to D0
    static class TodayFromMarkRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            Object markObj = fullModel.getValueAt(modelRow, LOG_COL_MARKPRICE);
            double mark = parseDoubleSafe(markObj == null ? "" : markObj.toString());

            Object d0Obj = fullModel.getValueAt(modelRow, LOG_FIRST_DATE_COL);
            double d0 = extractAmount(d0Obj);

            String amt  = Double.isNaN(mark) ? "" : fmt2(mark);
            String diff = (Double.isNaN(mark) || Double.isNaN(d0)) ? "—"
                    : String.format(Locale.US, "%+.2f", (mark - d0));

            String toShow;
            switch (displayMode) {
                case AMOUNT: toShow = amt; break;
                case DIFF:   toShow = diff; break;
                default:     toShow = amt + " (" + diff + ")"; break;
            }

            super.getTableCellRendererComponent(table, toShow, isSelected, hasFocus, row, column);

            if (!"—".equals(diff)) {
                double d = 0;
                try { d = Double.parseDouble(diff.replace(",", "")); } catch (Exception ignore) {}
                if (d > 0)      setBackground(new Color(205, 245, 205));
                else if (d < 0) setBackground(new Color(255, 210, 210));
                else            setBackground(Color.WHITE);
            } else setBackground(Color.WHITE);

            setForeground(Color.BLACK);
            setText(toShow);
            return this;
        }

        private double extractAmount(Object cell) {
            if (cell == null) return Double.NaN;
            String s = cell.toString();
            int lb = s.indexOf('(');
            String amt = (lb >= 0 ? s.substring(0, lb).trim() : s.trim());
            try { return Double.parseDouble(amt.replace(",", "")); } catch (Exception e) { return Double.NaN; }
        }
    }

    static class YesGreenStickyRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String s = (value == null) ? "" : value.toString().trim();
            if ("Yes".equalsIgnoreCase(s)) {
                setBackground(new Color(0, 128, 0));
                setForeground(Color.WHITE);
            } else {
                setBackground(Color.WHITE);
                setForeground(Color.BLACK);
            }
            return this;
        }
    }

    static class RangeBarRenderer extends OverlayStripeRenderer {
        RangeBarRenderer() { setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); }
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String bar = (value != null) ? value.toString() : "";
            setText(bar);

            Color fg = Color.DARK_GRAY;
            setBackground(Color.WHITE);

            int vIndex   = bar.indexOf('V');
            int barStart = bar.indexOf('|');
            int barEnd   = bar.lastIndexOf('|');

            if (vIndex > barStart && barStart != -1 && barEnd > barStart) {
                int innerLen = (barEnd - barStart - 2);
                if (innerLen > 0) {
                    double position = (double) (vIndex - barStart - 1) / innerLen;
                    if (position <= 0.33)      fg = new Color(0, 150, 0);
                    else if (position <= 0.66) fg = new Color(255, 140, 0);
                    else                       fg = new Color(200, 0, 0);
                }
            }
            setForeground(fg);
            return this;
        }
    }

    static class TransparentSelectionPlainRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBackground(Color.WHITE);
            setForeground(Color.BLACK);
            return this;
        }
    }

    // ---------- CSV + utils ----------
    private static void writeCsv(Path out, List<String> header, List<String[]> rows,
                                 LocalDate windowNewest, LocalDate windowOldest) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            DateTimeFormatter hdrFmt = DateTimeFormatter.ofPattern("MM/dd/yy");
            bw.write("# WindowNewest," + (windowNewest == null ? "" : hdrFmt.format(windowNewest)) +
                    ",WindowOldest," + (windowOldest == null ? "" : hdrFmt.format(windowOldest)));
            bw.newLine();
            bw.write(String.join(",", header));
            bw.newLine();
            for (String[] r : rows) {
                bw.write(String.join(",", r));
                bw.newLine();
            }
        }
    }

    private static String fmt2(double v) {
        if (Double.isNaN(v)) return "";
        return String.format(Locale.US, "%.2f", v);
    }
    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }
    private static long lng(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }
    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (Exception e) { return Double.NaN; }
    }
    private static LocalDate parseDateSafe(String s) {
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignore) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("M/d/yy")); } catch (DateTimeParseException ignore) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("M/d/yyyy")); } catch (DateTimeParseException ignore) {}
        return LocalDate.now();
    }

    private static double parsePercent(Object v) {
        String s = safeStr(v);
        try { return Double.parseDouble(s.replace("%","").trim()); } catch (Exception e) { return Double.NaN; }
    }
    private static long parseLong(Object v) {
        String s = safeStr(v).replace(",", "").trim();
        try { return Long.parseLong(s); } catch (Exception e) { return Long.MIN_VALUE; }
    }
    private static boolean isYes(Object v) {
        String s = safeStr(v).trim();
        return s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("true");
    }
    private static double parseDouble(Object v) {
        String s = safeStr(v).replace(",", "").trim();
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }
    private static String safeStr(Object v) { return v == null ? "" : v.toString(); }

    private static final Pattern AMT_DIFF = Pattern.compile("^\\s*([+-]?[0-9]*\\.?[0-9]+)\\s*(?:\\(([^)]+)\\))?\\s*$");
    private static double parseAmountOrDiff(Object v, DisplayMode mode) {
        String s = safeStr(v);
        Matcher m = AMT_DIFF.matcher(s);
        if (!m.matches()) return Double.NaN;
        String amt = m.group(1);
        String diff = m.group(2);
        try {
            switch (mode) {
                case AMOUNT: return Double.parseDouble(amt);
                case DIFF:
                    if (diff == null || diff.equals("—")) return Double.NaN;
                    return Double.parseDouble(diff.replace(",", "").trim());
                default:
                    return Double.parseDouble(amt);
            }
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    static class NumericFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
            if (isValid(nextText(fb, offset, 0, string))) super.insertString(fb, offset, string, attr);
        }
        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
            if (isValid(nextText(fb, offset, length, text))) super.replace(fb, offset, length, text, attrs);
        }
        private String nextText(FilterBypass fb, int offset, int length, String insert) throws javax.swing.text.BadLocationException {
            String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
            StringBuilder sb = new StringBuilder(cur);
            sb.replace(offset, offset+length, insert == null ? "" : insert);
            return sb.toString().trim();
        }
        private boolean isValid(String s) {
            if (s.isEmpty() || s.equals("+") || s.equals("-") || s.equals(".")) return true;
            try { Double.parseDouble(s.replace(",", "")); return true; }
            catch (Exception e) { return false; }
        }
    }

    private static HashMap<String, String> fetchsnapshot(String API_KEY) throws IOException {
        HashMap<String, String> map = new HashMap<>();

        // Fetch snapshot data
        String urlStr = "https://api.polygon.io/v2/snapshot/locale/us/markets/stocks/tickers?apiKey=" + API_KEY;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder json = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) json.append(line);
        in.close();

        JSONObject obj = new JSONObject(json.toString());
        JSONArray tickers = obj.getJSONArray("tickers");


        for (int i = 0; i < tickers.length(); i++) {
            JSONObject t = tickers.getJSONObject(i);
            JSONObject day = t.optJSONObject("day");
            JSONObject lastTrade = t.optJSONObject("lastTrade");
            JSONObject prevDay = t.optJSONObject("prevDay");

            String ticker = t.optString("ticker");


            //get P.E value
            //  String Spe = peRatios.get(ticker);

            double last = lastTrade != null ? lastTrade.optDouble("p", 0.0) : 0.0;
            String sl = String.valueOf(last);
            map.put(ticker, sl);


        }
        return map;
    }

}