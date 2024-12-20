/*
 * Copyright Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.swatch.billable.usage.admin.api;

import static java.util.Optional.ofNullable;

import com.redhat.swatch.billable.usage.configuration.ApplicationConfiguration;
import com.redhat.swatch.billable.usage.data.BillableUsageRemittanceEntity;
import com.redhat.swatch.billable.usage.data.BillableUsageRemittanceFilter;
import com.redhat.swatch.billable.usage.data.BillableUsageRemittanceRepository;
import com.redhat.swatch.billable.usage.data.RemittanceErrorCode;
import com.redhat.swatch.billable.usage.data.RemittanceStatus;
import com.redhat.swatch.billable.usage.data.RemittanceSummaryProjection;
import com.redhat.swatch.billable.usage.model.RemittanceMapper;
import com.redhat.swatch.billable.usage.openapi.model.MonthlyRemittance;
import com.redhat.swatch.billable.usage.openapi.model.TallyRemittance;
import com.redhat.swatch.billable.usage.services.BillingProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.candlepin.subscriptions.billable.usage.BillableUsage;

@Slf4j
@ApplicationScoped
@AllArgsConstructor
public class InternalBillableUsageController {
  private final BillableUsageRemittanceRepository remittanceRepository;
  private final BillingProducer billingProducer;
  private final RemittanceMapper remittanceMapper;
  private final ApplicationConfiguration applicationConfiguration;

  public List<MonthlyRemittance> getRemittances(BillableUsageRemittanceFilter filter) {
    if (filter.getOrgId() == null) {
      log.debug("Must provide orgId in query");
      return Collections.emptyList();
    }
    MonthlyRemittance emptyRemittance =
        new MonthlyRemittance()
            .orgId(filter.getOrgId())
            .productId(filter.getProductId())
            .metricId(filter.getMetricId())
            .billingProvider(filter.getBillingProvider())
            .billingAccountId(filter.getBillingAccountId())
            .remittedValue(0.0);
    var summaries = remittanceRepository.getRemittanceSummaries(filter);
    List<MonthlyRemittance> accountRemittanceList = transformUsageToMonthlyRemittance(summaries);
    if (accountRemittanceList.isEmpty()) {
      log.debug("This Account Remittance could not be found.");
      return List.of(emptyRemittance);
    }
    log.debug("Found {} matches for Org Id: {}", accountRemittanceList.size(), filter.getOrgId());
    return accountRemittanceList;
  }

  public List<TallyRemittance> getRemittancesByTally(BillableUsageRemittanceFilter filter) {
    return remittanceMapper.map(remittanceRepository.find(filter));
  }

  @Transactional
  public int resetBillableUsageRemittance(
      String productId,
      OffsetDateTime start,
      OffsetDateTime end,
      Set<String> orgIds,
      Set<String> billingAccountIds) {
    return remittanceRepository.resetBillableUsageRemittance(
        productId, start, end, orgIds, billingAccountIds);
  }

  @Transactional
  public void deleteDataForOrg(String orgId) {
    remittanceRepository.deleteByOrgId(orgId);
  }

  public long processRetries(OffsetDateTime asOf) {
    long total = remittanceRepository.countRemittancesByRetryAfterLessThan(asOf);
    if (total > 0) {
      long current = 0;
      List<BillableUsageRemittanceEntity> remittances =
          findNextRemittancesByRetryAfterLessThan(asOf);
      while (current < total && !remittances.isEmpty()) {
        remittances.forEach(this::processRetryForUsage);
        current += remittances.size();
        remittances = findNextRemittancesByRetryAfterLessThan(asOf);
      }
    }

    return total;
  }

  @Transactional
  List<BillableUsageRemittanceEntity> findNextRemittancesByRetryAfterLessThan(OffsetDateTime asOf) {
    return remittanceRepository.findNextRemittancesByRetryAfterLessThan(
        asOf, applicationConfiguration.getRetryRemittancesBatchSize());
  }

  @Transactional
  void updateRemittance(
      BillableUsageRemittanceEntity remittance,
      RemittanceStatus status,
      OffsetDateTime retryAfter,
      RemittanceErrorCode errorCode) {
    remittance.setRetryAfter(retryAfter);
    remittance.setErrorCode(errorCode);
    remittance.setStatus(status);
    // using merge because remittance is a detached entity
    remittanceRepository.merge(remittance);
  }

  private void processRetryForUsage(BillableUsageRemittanceEntity remittance) {
    log.info("Processing retry of remittance {}", remittance);
    RemittanceStatus previousStatus = remittance.getStatus();
    RemittanceErrorCode previousErrorCode = remittance.getErrorCode();
    OffsetDateTime previousRetryAfter = remittance.getRetryAfter();
    // reset the retry after column
    updateRemittance(remittance, RemittanceStatus.PENDING, null, null);
    try {
      // re-trigger billable usage
      billingProducer.produce(toBillableUsage(remittance));
    } catch (Exception ex) {
      log.warn("Remittance {} failed to be sent over kafka. Restoring.", remittance);
      // restoring previous state of the remittance because it fails to be sent
      updateRemittance(remittance, previousStatus, previousRetryAfter, previousErrorCode);
      throw ex;
    }
  }

  private BillableUsage toBillableUsage(BillableUsageRemittanceEntity remittance) {
    // Remove this null assignment once we start adding statuses in prod
    // https://issues.redhat.com/browse/SWATCH-2289
    var remittanceStatus =
        Objects.nonNull(remittance.getStatus())
            ? BillableUsage.Status.fromValue(remittance.getStatus().getValue())
            : null;
    return new BillableUsage()
        .withOrgId(remittance.getOrgId())
        .withTallyId(remittance.getTallyId())
        .withUuid(remittance.getUuid())
        .withSnapshotDate(remittance.getRemittancePendingDate())
        .withProductId(remittance.getProductId())
        .withSla(BillableUsage.Sla.fromValue(remittance.getSla()))
        .withUsage(BillableUsage.Usage.fromValue(remittance.getUsage()))
        .withBillingProvider(
            BillableUsage.BillingProvider.fromValue(remittance.getBillingProvider()))
        .withBillingAccountId(remittance.getBillingAccountId())
        .withMetricId(remittance.getMetricId())
        .withValue(remittance.getRemittedPendingValue())
        .withHardwareMeasurementType(remittance.getHardwareMeasurementType())
        .withStatus(remittanceStatus);
  }

  private List<MonthlyRemittance> transformUsageToMonthlyRemittance(
      List<RemittanceSummaryProjection> remittanceSummaryProjections) {
    List<MonthlyRemittance> remittances = new ArrayList<>();
    if (remittanceSummaryProjections.isEmpty()) {
      return Collections.emptyList();
    }

    for (RemittanceSummaryProjection entity : remittanceSummaryProjections) {
      // Remove this null assignment once we start adding statuses in prod
      // https://issues.redhat.com/browse/SWATCH-2289
      var remittanceStatus =
          Objects.nonNull(entity.getStatus()) ? entity.getStatus().getValue() : "null";
      MonthlyRemittance accountRemittance =
          new MonthlyRemittance()
              .orgId(entity.getOrgId())
              .productId(entity.getProductId())
              .metricId(entity.getMetricId())
              .billingProvider(entity.getBillingProvider())
              .billingAccountId(entity.getBillingAccountId())
              .remittedValue(entity.getTotalRemittedPendingValue())
              .remittanceDate(entity.getRemittancePendingDate())
              .accumulationPeriod(entity.getAccumulationPeriod())
              .remittanceStatus(remittanceStatus)
              .remittanceErrorCode(
                  ofNullable(entity.getErrorCode())
                      .map(RemittanceErrorCode::getValue)
                      .orElse("null"));
      remittances.add(accountRemittance);
    }
    log.debug("Found {} remittances for this account", remittances.size());
    return remittances;
  }
}
