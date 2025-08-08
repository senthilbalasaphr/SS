package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class PastLowWithNames {

    // ===== CONFIG =====
    private static String API_KEY = "YOUR_POLYGON_API_KEY_HERE"; // or: API_KEY = key.getApiKey("polygon.apiKey")
    private static final String DATA_CSV = "/Users/baps/Documents/Twillo/SCFiles/output_365_days.csv";

    private static final int DAYS = 30;                // window size: D0..D29 (NEWEST -> OLDEST)
    private static final int MAX_TICKERS = 200000;     // cap ticker pagination if ever needed
    private static final boolean FETCH_LIVE_MARK = true; // live MarkPrice from Polygon
    private static final int LIVE_MARK_LIMIT = 150;      // only fetch live for first N symbols to avoid rate limits
    private static final int LIVE_MARK_THROTTLE_MS = 60; // gentle throttle between live calls (ms)
    // ===================

    // Column indexes in table:
    // 0 Symbol, 1 Description, 2 PctChange, 3 AvgVol, 4 NewLow?, 5 VolSpike?, 6 MarkPrice, 7 RangeBar, 8.. date columns
    private static final int NEW_LOW_COL     = 4;
    private static final int VOL_SPIKE_COL   = 5;
    private static final int MARK_PRICE_COL  = 6;
    private static final int RANGE_BAR_COL   = 7;
    private static final int FIRST_DATE_COL  = 8;

    public static void main(String[] args) {
        // If you have a key loader helper:
         API_KEY = key.getApiKey("polygon.apiKey");

        if (API_KEY == null || API_KEY.isBlank() || API_KEY.contains("YOUR_POLYGON_API_KEY_HERE")) {
            System.err.println("Set your Polygon API key first.");
            return;
        }

        try {
            // 1) Read CSV & group by symbol
            Map<String, java.util.List<String[]>> grouped = readAndGroup(DATA_CSV);
            Set<String> symbolsInFile = new LinkedHashSet<>(grouped.keySet());
            System.out.println("Symbols in CSV: " + symbolsInFile.size());

            // 2) Fetch names only for symbols we need
            Map<String, String> tickerNameMap = fetchSymbolsFor(symbolsInFile);
            System.out.println("Fetched names for: " + tickerNameMap.size() + " symbols");

            // 3) Build NEWEST 30-date global window (NEWEST -> OLDEST)
            java.util.List<LocalDate> dateWindowNewestToOldest = pickGlobalDateWindowNewest(grouped, DAYS);
            if (dateWindowNewestToOldest.isEmpty()) {
                System.err.println("No symbol had at least " + DAYS + " rows to build a window.");
                return;
            }
            LocalDate windowNewest = dateWindowNewestToOldest.get(0);
            LocalDate windowOldest = dateWindowNewestToOldest.get(dateWindowNewestToOldest.size() - 1);
            DateTimeFormatter hdrFmt = DateTimeFormatter.ofPattern("MM/dd/yy");
            System.out.println("Window (newest→oldest): " + hdrFmt.format(windowNewest) + " → " + hdrFmt.format(windowOldest));

            // 4) Build rows aligned to this window
            java.util.List<String[]> results = new ArrayList<>();
            int processed = 0, total = symbolsInFile.size();
            int liveCount = 0;

            for (String symbol : symbolsInFile) {
                if ((++processed % 200) == 0) {
                    System.out.println("Processed " + processed + " / " + total);
                }

                java.util.List<String[]> bars = grouped.get(symbol);
                if (bars == null) continue;

                // Map date -> row
                Map<LocalDate, String[]> byDate = new HashMap<>();
                for (String[] r : bars) {
                    LocalDate d = parseDateSafe(r[0]);
                    byDate.put(d, r);
                }

                // Collect aligned rows in NEWEST->OLDEST order; skip ticker if any date missing
                java.util.List<String[]> alignedNewestToOldest = new ArrayList<>(DAYS);
                boolean missing = false;
                for (LocalDate d : dateWindowNewestToOldest) {
                    String[] r = byDate.get(d);
                    if (r == null) { missing = true; break; }
                    alignedNewestToOldest.add(r);
                }
                if (missing) continue;

                String desc = tickerNameMap.getOrDefault(symbol, symbol);
                String[] rowValues = buildRowNewestToOldest(symbol, desc, alignedNewestToOldest); // includes D1.. diffs

                if (FETCH_LIVE_MARK && liveCount < LIVE_MARK_LIMIT) {
                    Double live = fetchLiveMarkPrice(symbol);
                    if (live != null) rowValues[MARK_PRICE_COL] = fmt2(live); // overwrite MarkPrice
                    liveCount++;
                    try { Thread.sleep(LIVE_MARK_THROTTLE_MS); } catch (InterruptedException ignored) {}
                }

                results.add(rowValues);
            }

            // 5) Header (with RangeBar inserted before date columns)
            java.util.List<String> header = new ArrayList<>(Arrays.asList(
                    "Symbol","Description","PctChange","AvgVol","NewLow?","VolSpike?","MarkPrice","RangeBar"
            ));
            for (LocalDate d : dateWindowNewestToOldest) header.add(hdrFmt.format(d));

            // 6) Write CSV (top line: window newest/oldest once)
            Path out = Paths.get("past_30_days_newest_to_oldest.csv");
            writeCsv(out, header, results, windowNewest, windowOldest);
            System.out.println("✅ Wrote: " + out.toAbsolutePath());

            // 7) JTable with light-gray transparent selection overlay + stripe (full row)
            String title = "Past 30 Days (Newest→Oldest: " + hdrFmt.format(windowNewest) + " → " + hdrFmt.format(windowOldest) + ")";
            SwingUtilities.invokeLater(() -> showTable(title, header, results));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- Row builder (NEWEST -> OLDEST) ----------
    // CSV row indices: [0]=Date, [1]=Symbol, [2]=Volume, [3]=Open, [4]=Close, [5]=High, [6]=Low, [7]=Transactions
    private static String[] buildRowNewestToOldest(String symbol, String desc, java.util.List<String[]> newestToOldest) {
        // Newest day fields
        double newestClose = dbl(newestToOldest.get(0)[4]); // D0 close (latest)
        double newestLow   = dbl(newestToOldest.get(0)[6]);
        double newestHigh  = dbl(newestToOldest.get(0)[5]);

        // PctChange vs oldest close in window
        double oldestClose = dbl(newestToOldest.get(newestToOldest.size()-1)[4]);
        double pctChange   = (oldestClose == 0) ? 0.0 : ((newestClose - oldestClose) / oldestClose) * 100.0;

        // Avg volume over the window
        long volSum = 0;
        for (String[] r : newestToOldest) volSum += lng(r[2]);
        long avgVol = Math.round((double) volSum / newestToOldest.size());

        // New low check within window
        double minLow = Double.POSITIVE_INFINITY;
        for (String[] r : newestToOldest) {
            double lo = dbl(r[6]);
            if (!Double.isNaN(lo) && lo < minLow) minLow = lo;
        }
        boolean newLow = dbl(newestToOldest.get(0)[6]) <= minLow + 1e-9; // newest Low is min → new low
        boolean volSpike = avgVol > 0 && lng(newestToOldest.get(0)[2]) >= 2L * avgVol; // newest vol >= 2x avg

        // MarkPrice (live may overwrite); fallback = newest close
        String markPriceStr = fmt2(newestClose);

        // Range bar (ASCII) for newest day
        String rangeBar = buildRangeBar(newestLow, newestHigh, newestClose);

        // Build row values
        String[] row = new String[8 + newestToOldest.size()]; // +1 for RangeBar
        int idx = 0;
        row[idx++] = symbol;
        row[idx++] = desc;
        row[idx++] = String.format(Locale.US, "%.2f%%", pctChange);
        row[idx++] = String.valueOf(avgVol);
        row[idx++] = newLow ? "Yes" : "No";
        row[idx++] = volSpike ? "Yes" : "No";
        row[idx++] = markPriceStr;
        row[idx++] = rangeBar;

        // D0 = newest close; D1..Dn = differences (newer - older)
        double prevClose = Double.NaN;
        for (int i = 0; i < newestToOldest.size(); i++) {
            double c = dbl(newestToOldest.get(i)[4]);
            if (i == 0) {
                row[idx++] = fmt2(c);     // D0 = close
            } else {
                double diff = (Double.isNaN(prevClose) || Double.isNaN(c)) ? Double.NaN : (prevClose - c);
                row[idx++] = (Double.isNaN(diff) ? "" : String.format(Locale.US, "%+.2f", diff)); // signed difference
            }
            prevClose = c; // for next diff
        }
        return row;
    }

    // Build ASCII range bar: "  180.00 |-----V----------| 200.00"
    private static String buildRangeBar(double low, double high, double last) {
        if (Double.isNaN(low) || Double.isNaN(high) || high <= low || Double.isNaN(last)) {
            return "";
        }
        int barLength = 20;
        double posRatio = (last - low) / (high - low);
        posRatio = Math.max(0, Math.min(1, posRatio));
        int pos = (int) Math.round(posRatio * (barLength - 1));

        String lowStr  = String.format(Locale.US, "%7.2f", low);
        String highStr = String.format(Locale.US, "%7.2f", high);

        StringBuilder sb = new StringBuilder(lowStr).append(" ").append("|");
        for (int i = 0; i < barLength; i++) {
            sb.append(i == pos ? "V" : "-");
        }
        sb.append("| ").append(highStr);
        return sb.toString();
    }

    // ---------- Read & group CSV ----------
    private static Map<String, java.util.List<String[]>> readAndGroup(String filePath) throws IOException {
        Map<String, java.util.List<String[]>> map = new HashMap<>();
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

    // ---------- Pick global NEWEST N dates, return NEWEST -> OLDEST ----------
    private static java.util.List<LocalDate> pickGlobalDateWindowNewest(Map<String, java.util.List<String[]>> grouped, int days) {
        // Maintain only the newest N dates using a TreeSet, then reverse
        TreeSet<LocalDate> last = new TreeSet<>();
        for (java.util.List<String[]> rows : grouped.values()) {
            for (String[] r : rows) {
                LocalDate d = parseDateSafe(r[0]);
                last.add(d);
                if (last.size() > days) last.pollFirst(); // drop oldest beyond window
            }
        }
        if (last.size() < days) return Collections.emptyList();
        ArrayList<LocalDate> oldestToNewest = new ArrayList<>(last);
        Collections.reverse(oldestToNewest); // now NEWEST -> OLDEST
        return oldestToNewest;
    }

    // ---------- Fetch names only for symbols we need ----------
    private static Map<String, String> fetchSymbolsFor(Set<String> needed) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (needed.isEmpty()) return map;

        String nextUrl = "https://api.polygon.io/v3/reference/tickers?market=stocks&active=true&limit=1000&apiKey=" + API_KEY;
        while (nextUrl != null && map.size() < needed.size() && map.size() < MAX_TICKERS) {
            JSONObject obj = httpGetJson(nextUrl);
            JSONArray results = obj.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length() && map.size() < needed.size() && map.size() < MAX_TICKERS; i++) {
                    JSONObject t = results.getJSONObject(i);
                    String ticker = t.optString("ticker", "").trim();
                    if (!needed.contains(ticker)) continue;
                    String name = t.optString("name", "").trim();
                    if (!ticker.isEmpty() && !name.isEmpty()) map.put(ticker, name);
                }
            }
            String nextToken = obj.optString("next_url", null);
            nextUrl = (nextToken != null && !nextToken.isEmpty()) ? nextToken + "&apiKey=" + API_KEY : null;
        }
        return map;
    }

    // ---------- Live last trade price for MarkPrice ----------
    private static Double fetchLiveMarkPrice(String symbol) {
        try {
            String url = "https://api.polygon.io/v2/last/trade/" + symbol + "?apiKey=" + API_KEY;
            JSONObject obj = httpGetJson(url);
            JSONObject trade = obj.optJSONObject("results");
            if (trade != null) {
                double p = trade.optDouble("p", Double.NaN);
                if (!Double.isNaN(p)) return p;
            }
        } catch (Exception e) {
            // ignore; fallback handled by caller
        }
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

    // ======= Renderers with semi-transparent selection overlay (full row) =======

    // Base class: draws light-gray translucent overlay + left stripe when selected (without changing base colors)
    static abstract class OverlayStripeRenderer extends DefaultTableCellRenderer {
        private boolean overlaySelected = false;
        OverlayStripeRenderer() { setOpaque(true); }
        protected void setOverlaySelected(boolean selected) { this.overlaySelected = selected; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (overlaySelected) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Semi-transparent light gray overlay (keeps underlying colors visible)
                g2.setComposite(AlphaComposite.SrcOver.derive(0.30f)); // ~30% opacity
                g2.setColor(new Color(200, 200, 200));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setComposite(AlphaComposite.SrcOver);
                // Left selection stripe
                g2.setColor(new Color(120,120,120));
                g2.fillRect(0, 0, 3, getHeight());
                g2.dispose();
            }
        }
    }

    // Date columns:
    // D0 (FIRST_DATE_COL) shows latest close (no color).
    // D1..Dn show differences; + = green, - = red, 0 = white.
    static class DiffRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, hasFocus, row, column);
            setOverlaySelected(isSelected);

            int dateIndex = column - FIRST_DATE_COL; // 0 = D0, 1.. = diffs
            Color bg = Color.WHITE;
            Color fg = Color.BLACK;

            if (dateIndex == 0) {
                // D0 = latest close → neutral
                setBackground(bg);
                setForeground(fg);
                return this;
            }

            // D1..Dn = signed differences; parse the displayed text
            double diff = parseSigned(value);
            if (!Double.isNaN(diff)) {
                if (diff > 0) bg = new Color(205, 245, 205);    // light green
                else if (diff < 0) bg = new Color(255, 210, 210); // light red
            }
            setBackground(bg);
            setForeground(fg);
            return this;
        }

        private double parseSigned(Object value) {
            if (value == null) return Double.NaN;
            String s = value.toString().trim();
            if (s.isEmpty()) return Double.NaN;
            try {
                // strip any commas, keep sign
                s = s.replace(",", "");
                return Double.parseDouble(s);
            } catch (Exception e) {
                return Double.NaN;
            }
        }
    }

    // "Yes" cells: sticky green background + white text; add overlay/stripe on selection
    static class YesGreenStickyRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, hasFocus, row, column);
            setOverlaySelected(isSelected);

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

    // ASCII range bar with colored text by position; add overlay/stripe on selection
    static class RangeBarRenderer extends OverlayStripeRenderer {
        RangeBarRenderer() { setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, hasFocus, row, column);
            setOverlaySelected(isSelected);

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
                    if (position <= 0.33)      fg = new Color(0, 150, 0);    // green (near low)
                    else if (position <= 0.66) fg = new Color(255, 140, 0);  // orange (mid)
                    else                       fg = new Color(200, 0, 0);    // red (near high)
                }
            }
            setForeground(fg);
            return this;
        }
    }

    // Plain cells: white/black; add overlay/stripe on selection
    static class TransparentSelectionPlainRenderer extends OverlayStripeRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, hasFocus, row, column);
            setOverlaySelected(isSelected);
            setBackground(Color.WHITE);
            setForeground(Color.BLACK);
            return this;
        }
    }

    // ---------- JTable & wiring ----------
    private static void showTable(String title, java.util.List<String> header, java.util.List<String[]> rows) {
        Vector<String> cols = new Vector<>(header);
        Vector<Vector<String>> data = new Vector<>();
        for (String[] r : rows) data.add(new Vector<>(Arrays.asList(r)));

        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);

        // Full row selection
        table.setCellSelectionEnabled(false);
        table.setRowSelectionAllowed(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.setRowHeight(22);

        // Force click to select entire row
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow >= 0) {
                    table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                } else {
                    table.clearSelection();
                }
            }
        });

        // Selection background is irrelevant (we draw overlay), but keep it transparent
        table.setSelectionBackground(new Color(0, 0, 0, 0));
        table.setSelectionForeground(Color.BLACK);

        // Apply renderers:
        DiffRenderer diffRenderer = new DiffRenderer();
        for (int col = FIRST_DATE_COL; col < table.getColumnCount(); col++) {
            table.getColumnModel().getColumn(col).setCellRenderer(diffRenderer);
        }
        YesGreenStickyRenderer yesSticky = new YesGreenStickyRenderer();
        table.getColumnModel().getColumn(NEW_LOW_COL).setCellRenderer(yesSticky);
        table.getColumnModel().getColumn(VOL_SPIKE_COL).setCellRenderer(yesSticky);
        table.getColumnModel().getColumn(RANGE_BAR_COL).setCellRenderer(new RangeBarRenderer());
        TransparentSelectionPlainRenderer plain = new TransparentSelectionPlainRenderer();
        table.getColumnModel().getColumn(0).setCellRenderer(plain);
        table.getColumnModel().getColumn(1).setCellRenderer(plain);
        table.getColumnModel().getColumn(2).setCellRenderer(plain);
        table.getColumnModel().getColumn(3).setCellRenderer(plain);
        table.getColumnModel().getColumn(MARK_PRICE_COL).setCellRenderer(plain);

        // Clean white background
        table.setOpaque(true);
        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setOpaque(true);
        sp.getViewport().setBackground(Color.WHITE);

        JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        f.add(sp, BorderLayout.CENTER);
        f.setSize(1800, 900);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    // ---------- CSV + utils ----------
    private static void writeCsv(Path out, java.util.List<String> header, java.util.List<String[]> rows,
                                 LocalDate windowNewest, LocalDate windowOldest) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            DateTimeFormatter hdrFmt = DateTimeFormatter.ofPattern("MM/dd/yy");
            bw.write("# WindowNewest," + (windowNewest == null ? "" : hdrFmt.format(windowNewest)) +
                    ",WindowOldest," + (windowOldest == null ? "" : hdrFmt.format(windowOldest)));
            bw.newLine();

            bw.write(String.join(",", header));
            bw.newLine();
            for (String[] r : rows) {
                bw.write(String.join(",", r)); // values already formatted
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
    private static LocalDate parseDateSafe(String s) {
        try { return LocalDate.parse(s); } catch (DateTimeParseException ignore) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("M/d/yy")); } catch (DateTimeParseException ignore) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("M/d/yyyy")); } catch (DateTimeParseException ignore) {}
        return LocalDate.now();
    }
}
