/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.ComponentParseUtils;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivityIntentInfo;
import android.content.pm.parsing.PackageImpl;
import android.content.pm.parsing.ParsingPackage;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.annotation.NonNull;

import com.android.server.om.OverlayReferenceMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@RunWith(JUnit4.class)
public class AppsFilterTest {

    private static final int DUMMY_CALLING_UID = 10345;
    private static final int DUMMY_TARGET_UID = 10556;
    private static final int DUMMY_ACTOR_UID = 10656;
    private static final int DUMMY_OVERLAY_UID = 10756;
    private static final int DUMMY_ACTOR_TWO_UID = 10856;

    @Mock
    AppsFilter.FeatureConfig mFeatureConfigMock;

    private ArrayMap<String, PackageSetting> mExisting = new ArrayMap<>();

    private static ParsingPackage pkg(String packageName) {
        return PackageImpl.forParsing(packageName)
                .setTargetSdkVersion(Build.VERSION_CODES.R);
    }

    private static ParsingPackage pkg(String packageName, Intent... queries) {
        ParsingPackage pkg = pkg(packageName);
        if (queries != null) {
            for (Intent intent : queries) {
                pkg.addQueriesIntent(intent);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, String... queriesPackages) {
        ParsingPackage pkg = pkg(packageName);
        if (queriesPackages != null) {
            for (String queryPackageName : queriesPackages) {
                pkg.addQueriesPackage(queryPackageName);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, IntentFilter... filters) {
        ParsedActivity activity = new ParsedActivity();
        activity.setPackageName(packageName);
        for (IntentFilter filter : filters) {
            final ParsedActivityIntentInfo info = new ParsedActivityIntentInfo(packageName, null);
            if (filter.countActions() > 0) {
                filter.actionsIterator().forEachRemaining(info::addAction);
            }
            if (filter.countCategories() > 0) {
                filter.actionsIterator().forEachRemaining(info::addAction);
            }
            if (filter.countDataAuthorities() > 0) {
                filter.authoritiesIterator().forEachRemaining(info::addDataAuthority);
            }
            if (filter.countDataSchemes() > 0) {
                filter.schemesIterator().forEachRemaining(info::addDataScheme);
            }
            activity.addIntent(info);
            activity.exported = true;
        }

        return pkg(packageName)
                .addActivity(activity);
    }

    private static ParsingPackage pkgWithProvider(String packageName, String authority) {
        ComponentParseUtils.ParsedProvider provider = new ComponentParseUtils.ParsedProvider();
        provider.setPackageName(packageName);
        provider.setExported(true);
        provider.setAuthority(authority);
        return pkg(packageName)
                .addProvider(provider);
    }

    @Before
    public void setup() throws Exception {
        mExisting = new ArrayMap<>();

        MockitoAnnotations.initMocks(this);
        when(mFeatureConfigMock.isGloballyEnabled()).thenReturn(true);
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class)))
                .thenReturn(true);
    }

    @Test
    public void testSystemReadyPropogates() throws Exception {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();
        verify(mFeatureConfigMock).onSystemReady();
    }

    @Test
    public void testQueriesAction_FilterMatches() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package", new IntentFilter("TEST_ACTION")), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesProvider_FilterMatches() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package",
                        new Intent().setData(Uri.parse("content://com.some.authority"))),
                DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesDifferentProvider_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package",
                        new Intent().setData(Uri.parse("content://com.some.other.authority"))),
                DUMMY_CALLING_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesProviderWithSemiColon_FilterMatches() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority;com.some.other.authority"),
                DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package",
                        new Intent().setData(Uri.parse("content://com.some.authority"))),
                DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesAction_NoMatchingAction_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesAction_NoMatchingActionFilterLowSdk_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package",
                        new Intent("TEST_ACTION"))
                        .setTargetSdkVersion(Build.VERSION_CODES.P),
                DUMMY_CALLING_UID);

        when(mFeatureConfigMock.packageIsEnabled(calling.pkg)).thenReturn(false);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testNoQueries_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testForceQueryable_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                        pkg("com.some.package").setForceQueryable(true), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testForceQueryableByDevice_SystemCaller_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{"com.some.package"}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testForceQueryableByDevice_NonSystemCaller_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{"com.some.package"}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }


    @Test
    public void testSystemQueryable_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{},
                        true /* system force queryable */, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testQueriesPackage_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", "com.some.package"), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testNoQueries_FeatureOff_DoesntFilter() {
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class)))
                .thenReturn(false);
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(
                appsFilter, pkg("com.some.package"), DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(
                appsFilter, pkg("com.some.other.package"), DUMMY_CALLING_UID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testSystemUid_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);

        assertFalse(appsFilter.shouldFilterApplication(0, null, target, 0));
        assertFalse(appsFilter.shouldFilterApplication(Process.FIRST_APPLICATION_UID - 1,
                null, target, 0));
    }

    @Test
    public void testNonSystemUid_NoCallingSetting_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, null, target, 0));
    }

    @Test
    public void testNoTargetPackage_filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = new PackageSettingBuilder()
                .setName("com.some.package")
                .setCodePath("/")
                .setResourcePath("/")
                .setPVersionCode(1L)
                .build();
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_UID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testActsOnTargetOfOverlay() {
        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setIsOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetName("overlayableName");
        ParsingPackage actor = pkg("com.some.package.actor");

        final AppsFilter appsFilter = new AppsFilter(mFeatureConfigMock, new String[]{}, false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        if (actorName.equals(actorString)) {
                            return actor.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                });
        appsFilter.onSystemReady();

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_UID);
        PackageSetting overlaySetting = simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_UID);
        PackageSetting actorSetting = simulateAddPackage(appsFilter, actor, DUMMY_ACTOR_UID);

        // Actor can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_UID, actorSetting,
                targetSetting, 0));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_UID, actorSetting,
                overlaySetting, 0));

        // But target/overlay can't see each other
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_UID, targetSetting,
                overlaySetting, 0));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_UID, overlaySetting,
                targetSetting, 0));

        // And can't see the actor
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_UID, targetSetting,
                actorSetting, 0));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_UID, overlaySetting,
                actorSetting, 0));
    }

    @Test
    public void testActsOnTargetOfOverlayThroughSharedUser() {
        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setIsOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetName("overlayableName");
        ParsingPackage actorOne = pkg("com.some.package.actor.one");
        ParsingPackage actorTwo = pkg("com.some.package.actor.two");

        final AppsFilter appsFilter = new AppsFilter(mFeatureConfigMock, new String[]{}, false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        // Only actorOne is mapped as a valid actor
                        if (actorName.equals(actorString)) {
                            return actorOne.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                });
        appsFilter.onSystemReady();

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_UID);
        PackageSetting overlaySetting = simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_UID);
        PackageSetting actorOneSetting = simulateAddPackage(appsFilter, actorOne, DUMMY_ACTOR_UID);
        PackageSetting actorTwoSetting = simulateAddPackage(appsFilter, actorTwo,
                DUMMY_ACTOR_TWO_UID);

        SharedUserSetting actorSharedSetting = new SharedUserSetting("actorSharedUser",
                actorOneSetting.pkgFlags, actorOneSetting.pkgPrivateFlags);
        actorSharedSetting.addPackage(actorOneSetting);
        actorSharedSetting.addPackage(actorTwoSetting);

        // actorTwo can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_TWO_UID, actorSharedSetting,
                targetSetting, 0));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_TWO_UID, actorSharedSetting,
                overlaySetting, 0));
    }

    @Test
    public void testInitiatingApp_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_UID, withInstallSource(target.name, null, null, false));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testUninstalledInitiatingApp_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_UID, withInstallSource(target.name, null, null, true));

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testOriginatingApp_Filters() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_UID, withInstallSource(null, target.name, null, false));

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    @Test
    public void testInstallingApp_DoesntFilter() {
        final AppsFilter appsFilter =
                new AppsFilter(mFeatureConfigMock, new String[]{}, false, null);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_UID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_UID, withInstallSource(null, null, target.name, false));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_UID, calling, target, 0));
    }

    private interface WithSettingBuilder {
        PackageSettingBuilder withBuilder(PackageSettingBuilder builder);
    }

    private PackageSetting simulateAddPackage(AppsFilter filter,
            ParsingPackage newPkgBuilder, int appId) {
        return simulateAddPackage(filter, newPkgBuilder, appId, null);
    }

    private PackageSetting simulateAddPackage(AppsFilter filter,
            ParsingPackage newPkgBuilder, int appId, @Nullable WithSettingBuilder action) {
        AndroidPackage newPkg = newPkgBuilder.hideAsParsed().hideAsFinal();

        final PackageSettingBuilder settingBuilder = new PackageSettingBuilder()
                .setPackage(newPkg)
                .setAppId(appId)
                .setName(newPkg.getPackageName())
                .setCodePath("/")
                .setResourcePath("/")
                .setPVersionCode(1L);
        final PackageSetting setting =
                (action == null ? settingBuilder : action.withBuilder(settingBuilder)).build();
        filter.addPackage(setting, mExisting);
        mExisting.put(newPkg.getPackageName(), setting);
        return setting;
    }

    private WithSettingBuilder withInstallSource(String initiatingPackageName,
            String originatingPackageName, String installerPackageName,
            boolean isInitiatingPackageUninstalled) {
        final InstallSource installSource = InstallSource.create(initiatingPackageName,
                originatingPackageName, installerPackageName,
                /* isOrphaned= */ false, isInitiatingPackageUninstalled);
        return setting -> setting.setInstallSource(installSource);
    }
}

