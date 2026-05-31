package org.amit.finwise.cfo.model;

public enum DataQualityFlag {
    OK,
    SUSPECT_GAP,  // Large unexplained price move — likely a split or data error; excluded from returns
    IMPUTED       // Value filled in from adjacent data
}
