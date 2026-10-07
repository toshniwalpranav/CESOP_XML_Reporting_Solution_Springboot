package com.example.cesop.collect;

import com.example.cesop.model.CesopModel.DipHeader;
import com.example.cesop.model.CesopModel.Psp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

public final class CollectModel {
    private CollectModel() {}

    /**
     * Settings for one reporting run.
     * @param threshold                  payee is reportable when counted payments are MORE than this (default 25)
     * @param countRefundsForThreshold   false (default) = refunds are reported but not counted towards the threshold.
     *                                   Verify this against the current EU Commission guidance for your case.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectConfig(DipHeader header, Psp sendingPsp, Psp reportingPsp, Integer quarter, Integer year,
                                String transmittingCountry, Integer threshold, Boolean countRefundsForThreshold) {}

    public record PayeeSummary(String accountType, String account, String name, String accountCountry,
                               int crossBorderPayments, int countedForThreshold, boolean reportable) {}

    /** What the run did with the CSV. Nothing is dropped silently: every row is counted somewhere here. */
    public record CollectReport(int rowsRead, int rowsWithErrors, int outsidePeriod, int payerOutsideEu,
                                int notCrossBorder, int crossBorderRows, int threshold,
                                int payeesFound, int payeesReportable, String messageTypeIndic,
                                List<PayeeSummary> payees, List<String> rowErrors) {}
}
