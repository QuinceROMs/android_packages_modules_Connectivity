/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.PERMISSION_INTERNET;

import static com.android.server.BpfNetMaps.DOZABLE_MATCH;
import static com.android.server.BpfNetMaps.HAPPY_BOX_MATCH;
import static com.android.server.BpfNetMaps.IIF_MATCH;
import static com.android.server.BpfNetMaps.NO_MATCH;
import static com.android.server.BpfNetMaps.PENALTY_BOX_MATCH;
import static com.android.server.BpfNetMaps.POWERSAVE_MATCH;
import static com.android.server.BpfNetMaps.RESTRICTED_MATCH;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;
import android.os.ServiceSpecificException;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct.U32;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.TestBpfMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfNetMapsTest {
    private static final String TAG = "BpfNetMapsTest";

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final int TEST_UID = 10086;
    private static final int[] TEST_UIDS = {10002, 10003};
    private static final String TEST_IF_NAME = "wlan0";
    private static final int TEST_IF_INDEX = 7;
    private static final int NO_IIF = 0;
    private static final String CHAINNAME = "fw_dozable";
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(0);
    private static final List<Integer> FIREWALL_CHAINS = List.of(
            FIREWALL_CHAIN_DOZABLE,
            FIREWALL_CHAIN_STANDBY,
            FIREWALL_CHAIN_POWERSAVE,
            FIREWALL_CHAIN_RESTRICTED,
            FIREWALL_CHAIN_LOW_POWER_STANDBY,
            FIREWALL_CHAIN_OEM_DENY_1,
            FIREWALL_CHAIN_OEM_DENY_2,
            FIREWALL_CHAIN_OEM_DENY_3
    );

    private BpfNetMaps mBpfNetMaps;

    @Mock INetd mNetd;
    private final BpfMap<U32, U32> mConfigurationMap = new TestBpfMap<>(U32.class, U32.class);
    private final BpfMap<U32, UidOwnerValue> mUidOwnerMap =
            new TestBpfMap<>(U32.class, UidOwnerValue.class);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        BpfNetMaps.setConfigurationMapForTest(mConfigurationMap);
        BpfNetMaps.setUidOwnerMapForTest(mUidOwnerMap);
        mBpfNetMaps = new BpfNetMaps(mNetd);
    }

    @Test
    public void testBpfNetMapsBeforeT() throws Exception {
        assumeFalse(SdkLevel.isAtLeastT());
        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        mBpfNetMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }

    private void doTestIsChainEnabled(final List<Integer> enableChains) throws Exception {
        long match = 0;
        for (final int chain: enableChains) {
            match |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(match));

        for (final int chain: FIREWALL_CHAINS) {
            final String testCase = "EnabledChains: " + enableChains + " CheckedChain: " + chain;
            if (enableChains.contains(chain)) {
                assertTrue("Expected isChainEnabled returns True, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            } else {
                assertFalse("Expected isChainEnabled returns False, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            }
        }
    }

    private void doTestIsChainEnabled(final int enableChain) throws Exception {
        doTestIsChainEnabled(List.of(enableChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabled() throws Exception {
        doTestIsChainEnabled(FIREWALL_CHAIN_DOZABLE);
        doTestIsChainEnabled(FIREWALL_CHAIN_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_POWERSAVE);
        doTestIsChainEnabled(FIREWALL_CHAIN_RESTRICTED);
        doTestIsChainEnabled(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_1);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_2);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledMultipleChainEnabled() throws Exception {
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestIsChainEnabled(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(-1 /* childChain */));
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(1000 /* childChain */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.isChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    private void doTestSetChildChain(final List<Integer> testChains) throws Exception {
        long expectedMatch = 0;
        for (final int chain: testChains) {
            expectedMatch |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }

        assertEquals(0, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, true /* enable */);
        }
        assertEquals(expectedMatch, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, false /* enable */);
        }
        assertEquals(0, mConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);
    }

    private void doTestSetChildChain(final int testChain) throws Exception {
        doTestSetChildChain(List.of(testChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChain() throws Exception {
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(FIREWALL_CHAIN_DOZABLE);
        doTestSetChildChain(FIREWALL_CHAIN_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_POWERSAVE);
        doTestSetChildChain(FIREWALL_CHAIN_RESTRICTED);
        doTestSetChildChain(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_1);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_2);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainMultipleChain() throws Exception {
        mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestSetChildChain(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(-1 /* childChain */, true /* enable */));
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(1000 /* childChain */, true /* enable */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testSetChildChainBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true /* enable */));
    }

    private void checkUidOwnerValue(final long uid, final long expectedIif,
            final long expectedMatch) throws Exception {
        final UidOwnerValue config = mUidOwnerMap.getValue(new U32(uid));
        if (expectedMatch == 0) {
            assertNull(config);
        } else {
            assertEquals(expectedIif, config.iif);
            assertEquals(expectedMatch, config.rule);
        }
    }

    private void doTestRemoveNaughtyApp(final long iif, final long match) throws Exception {
        mUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(iif, match));

        mBpfNetMaps.removeNaughtyApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, iif, match & ~PENALTY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyApp() throws Exception {
        doTestRemoveNaughtyApp(NO_IIF, PENALTY_BOX_MATCH);

        // PENALTY_BOX_MATCH with other matches
        doTestRemoveNaughtyApp(NO_IIF, PENALTY_BOX_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH);

        // PENALTY_BOX_MATCH with IIF_MATCH
        doTestRemoveNaughtyApp(TEST_IF_INDEX, PENALTY_BOX_MATCH | IIF_MATCH);

        // PENALTY_BOX_MATCH is not enabled
        doTestRemoveNaughtyApp(NO_IIF, DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyAppMissingUid() {
        // UidOwnerMap does not have entry for TEST_UID
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.removeNaughtyApp(TEST_UID));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.removeNaughtyApp(TEST_UID));
    }

    private void doTestAddNaughtyApp(final long iif, final long match) throws Exception {
        if (match != NO_MATCH) {
            mUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(iif, match));
        }

        mBpfNetMaps.addNaughtyApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, iif, match | PENALTY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddNaughtyApp() throws Exception {
        doTestAddNaughtyApp(NO_IIF, NO_MATCH);

        // Other matches are enabled
        doTestAddNaughtyApp(NO_IIF, DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // IIF_MATCH is enabled
        doTestAddNaughtyApp(TEST_IF_INDEX, IIF_MATCH);

        // PENALTY_BOX_MATCH is already enabled
        doTestAddNaughtyApp(NO_IIF, PENALTY_BOX_MATCH | DOZABLE_MATCH);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testAddNaughtyAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.addNaughtyApp(TEST_UID));
    }

    private void doTestRemoveNiceApp(final long iif, final long match) throws Exception {
        mUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(iif, match));

        mBpfNetMaps.removeNiceApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, iif, match & ~HAPPY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceApp() throws Exception {
        doTestRemoveNiceApp(NO_IIF, HAPPY_BOX_MATCH);

        // HAPPY_BOX_MATCH with other matches
        doTestRemoveNiceApp(NO_IIF, HAPPY_BOX_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH);

        // HAPPY_BOX_MATCH with IIF_MATCH
        doTestRemoveNiceApp(TEST_IF_INDEX, HAPPY_BOX_MATCH | IIF_MATCH);

        // HAPPY_BOX_MATCH is not enabled
        doTestRemoveNiceApp(NO_IIF, DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceAppMissingUid() {
        // UidOwnerMap does not have entry for TEST_UID
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.removeNiceApp(TEST_UID));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.removeNiceApp(TEST_UID));
    }

    private void doTestAddNiceApp(final long iif, final long match) throws Exception {
        if (match != NO_MATCH) {
            mUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(iif, match));
        }

        mBpfNetMaps.addNiceApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, iif, match | HAPPY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddNiceApp() throws Exception {
        doTestAddNiceApp(NO_IIF, NO_MATCH);

        // Other matches are enabled
        doTestAddNiceApp(NO_IIF, DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // IIF_MATCH is enabled
        doTestAddNiceApp(TEST_IF_INDEX, IIF_MATCH);

        // HAPPY_BOX_MATCH is already enabled
        doTestAddNiceApp(NO_IIF, HAPPY_BOX_MATCH | DOZABLE_MATCH);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testAddNiceAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.addNiceApp(TEST_UID));
    }
}
