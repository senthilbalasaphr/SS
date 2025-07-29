package org.example;

class SummaryStats {
    public double low;
    public String lowDate;
    public double high;
    public String highDate;
    public long avgVolume;

    public SummaryStats(double low, String lowDate, double high, String highDate, long avgVolume) {
        this.low = low;
        this.lowDate = lowDate;
        this.high = high;
        this.highDate = highDate;
        this.avgVolume = avgVolume;
    }
}
