package io.github.long36708.updater.crypto;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;

@SuppressWarnings("deprecation")
public class FakePackageManager extends PackageManager {
    private final PackageManager real;
    private final String targetPackage;
    private final String realPackage;
    private final byte[] fakeCertBytes;

    public FakePackageManager(PackageManager real, String targetPackage, String realPackage, byte[] fakeCertBytes) {
        this.real = real;
        this.targetPackage = targetPackage;
        this.realPackage = realPackage;
        this.fakeCertBytes = fakeCertBytes;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        String lookup = targetPackage.equals(packageName) ? realPackage : packageName;
        PackageInfo pi = real.getPackageInfo(lookup, flags);
        if (targetPackage.equals(packageName)) {
            pi.packageName = targetPackage;
            if ((flags & GET_SIGNATURES) != 0) {
                pi.signatures = new Signature[]{ new Signature(fakeCertBytes) };
            }
            Log.d("FakePackageManager", "Injected for " + packageName + " (real: " + realPackage + ")");
        }
        return pi;
    }

    @Override
    public void addPackageToPreferred(java.lang.String p0) {
        real.addPackageToPreferred(p0);
    }

    @Override
    public boolean addPermission(android.content.pm.PermissionInfo p0) {
        return real.addPermission(p0);
    }

    @Override
    public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) {
        return real.addPermissionAsync(p0);
    }

    @Override
    public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) {
        real.addPreferredActivity(p0, p1, p2, p3);
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return real.canRequestPackageInstalls();
    }

    @Override
    public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) {
        return real.canonicalToCurrentPackageNames(p0);
    }

    @Override
    public int checkPermission(java.lang.String p0, java.lang.String p1) {
        return real.checkPermission(p0, p1);
    }

    @Override
    public int checkSignatures(int p0, int p1) {
        return real.checkSignatures(p0, p1);
    }

    @Override
    public int checkSignatures(java.lang.String p0, java.lang.String p1) {
        return real.checkSignatures(p0, p1);
    }

    @Override
    public void clearInstantAppCookie() {
        real.clearInstantAppCookie();
    }

    @Override
    public void clearPackagePreferredActivities(java.lang.String p0) {
        real.clearPackagePreferredActivities(p0);
    }

    @Override
    public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) {
        return real.currentToCanonicalPackageNames(p0);
    }

    @Override
    public void extendVerificationTimeout(int p0, int p1, long p2) {
        real.extendVerificationTimeout(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0)throws NameNotFoundException {
        return real.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0)throws NameNotFoundException {
        return real.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0)throws NameNotFoundException {
        return real.getActivityIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0)throws NameNotFoundException {
        return real.getActivityIcon(p0);
    }

    @Override
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1)throws NameNotFoundException {
        return real.getActivityInfo(p0, p1);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0)throws NameNotFoundException {
        return real.getActivityLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0)throws NameNotFoundException {
        return real.getActivityLogo(p0);
    }

    @Override
    public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) {
        return real.getAllPermissionGroups(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) {
        return real.getApplicationBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0)throws NameNotFoundException {
        return real.getApplicationBanner(p0);
    }

    @Override
    public int getApplicationEnabledSetting(java.lang.String p0) {
        return real.getApplicationEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) {
        return real.getApplicationIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0)throws NameNotFoundException {
        return real.getApplicationIcon(p0);
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.getApplicationInfo(p0, p1);
    }

    @Override
    public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) {
        return real.getApplicationLabel(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) {
        return real.getApplicationLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0)throws NameNotFoundException {
        return real.getApplicationLogo(p0);
    }

    @Override
    public android.content.pm.ChangedPackages getChangedPackages(int p0) {
        return real.getChangedPackages(p0);
    }

    @Override
    public int getComponentEnabledSetting(android.content.ComponentName p0) {
        return real.getComponentEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return real.getDefaultActivityIcon();
    }

    @Override
    public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return real.getDrawable(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int p0) {
        return real.getInstalledApplications(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int p0) {
        return real.getInstalledPackages(p0);
    }

    @Override
    public java.lang.String getInstallerPackageName(java.lang.String p0) {
        return real.getInstallerPackageName(p0);
    }

    @Override
    public byte[] getInstantAppCookie() {
        return real.getInstantAppCookie();
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return real.getInstantAppCookieMaxBytes();
    }

    @Override
    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1)throws NameNotFoundException {
        return real.getInstrumentationInfo(p0, p1);
    }

    @Override
    public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) {
        return real.getLaunchIntentForPackage(p0);
    }

    @Override
    public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) {
        return real.getLeanbackLaunchIntentForPackage(p0);
    }

    @Override
    public java.lang.String getNameForUid(int p0) {
        return real.getNameForUid(p0);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0)throws NameNotFoundException {
        return real.getPackageGids(p0);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.getPackageGids(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, int p1)throws NameNotFoundException {
        if (targetPackage.equals(p0.getPackageName())) {
            p0 = new android.content.pm.VersionedPackage(realPackage, p0.getVersionCode());
        }
        return real.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInstaller getPackageInstaller() {
        return real.getPackageInstaller();
    }

    @Override
    public int getPackageUid(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.getPackageUid(p0, p1);
    }

    @Override
    public java.lang.String[] getPackagesForUid(int p0) {
        return real.getPackagesForUid(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) {
        return real.getPackagesHoldingPermissions(p0, p1);
    }

    @Override
    public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.getPermissionGroupInfo(p0, p1);
    }

    @Override
    public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.getPermissionInfo(p0, p1);
    }

    @Override
    public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) {
        return real.getPreferredActivities(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) {
        return real.getPreferredPackages(p0);
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1)throws NameNotFoundException {
        return real.getProviderInfo(p0, p1);
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1)throws NameNotFoundException {
        return real.getReceiverInfo(p0, p1);
    }

    @Override
    public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0)throws NameNotFoundException {
        return real.getResourcesForActivity(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0)throws NameNotFoundException {
        return real.getResourcesForApplication(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(java.lang.String p0)throws NameNotFoundException {
        return real.getResourcesForApplication(p0);
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1)throws NameNotFoundException {
        return real.getServiceInfo(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) {
        return real.getSharedLibraries(p0);
    }

    @Override
    public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        return real.getSystemAvailableFeatures();
    }

    @Override
    public java.lang.String[] getSystemSharedLibraryNames() {
        return real.getSystemSharedLibraryNames();
    }

    @Override
    public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return real.getText(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) {
        return real.getUserBadgedDrawableForDensity(p0, p1, p2, p3);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) {
        return real.getUserBadgedIcon(p0, p1);
    }

    @Override
    public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) {
        return real.getUserBadgedLabel(p0, p1);
    }

    @Override
    public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return real.getXml(p0, p1, p2);
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0) {
        return real.hasSystemFeature(p0);
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0, int p1) {
        return real.hasSystemFeature(p0, p1);
    }

    @Override
    public boolean isInstantApp() {
        return real.isInstantApp();
    }

    @Override
    public boolean isInstantApp(java.lang.String p0) {
        return real.isInstantApp(p0);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) {
        return real.isPermissionRevokedByPolicy(p0, p1);
    }

    @Override
    public boolean isSafeMode() {
        return real.isSafeMode();
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) {
        return real.queryBroadcastReceivers(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) {
        return real.queryContentProviders(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) {
        return real.queryInstrumentation(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) {
        return real.queryIntentActivities(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) {
        return real.queryIntentActivityOptions(p0, p1, p2, p3);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) {
        return real.queryIntentContentProviders(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) {
        return real.queryIntentServices(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1)throws NameNotFoundException {
        return real.queryPermissionsByGroup(p0, p1);
    }

    @Override
    public void removePackageFromPreferred(java.lang.String p0) {
        real.removePackageFromPreferred(p0);
    }

    @Override
    public void removePermission(java.lang.String p0) {
        real.removePermission(p0);
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) {
        return real.resolveActivity(p0, p1);
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) {
        return real.resolveContentProvider(p0, p1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) {
        return real.resolveService(p0, p1);
    }

    @Override
    public void setApplicationCategoryHint(java.lang.String p0, int p1) {
        real.setApplicationCategoryHint(p0, p1);
    }

    @Override
    public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) {
        real.setApplicationEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) {
        real.setComponentEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) {
        real.setInstallerPackageName(p0, p1);
    }

    @Override
    public void updateInstantAppCookie(byte[] p0) {
        real.updateInstantAppCookie(p0);
    }

    @Override
    public void verifyPendingInstall(int p0, int p1) {
        real.verifyPendingInstall(p0, p1);
    }

}
