package org.casemgmt.search;

public record SearchFacetValue(String value, String label, long count, boolean countSuppressed) {}
