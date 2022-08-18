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
package org.candlepin.subscriptions.product;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.candlepin.subscriptions.task.TaskQueueProperties;
import org.candlepin.subscriptions.util.KafkaConsumerRegistry;
import org.junit.jupiter.api.Test;

class OfferingWorkerTest {

  @Test
  void testReceive() {
    // Given a SKU is allowlisted and retrievable from upstream,
    TaskQueueProperties props = mock(TaskQueueProperties.class);
    KafkaConsumerRegistry consumerReg = mock(KafkaConsumerRegistry.class);
    OfferingSyncController controller = mock(OfferingSyncController.class);

    when(controller.syncOffering(anyString())).thenReturn(SyncResult.FETCHED_AND_SYNCED);

    OfferingWorker subject = new OfferingWorker(props, consumerReg, controller);

    // When an allowlisted SKU is received,
    String sku = "RH00604F5";
    subject.receive(new OfferingSyncTask(sku));

    // Then the offering should be synced.
    verify(controller).syncOffering(sku);
  }
}