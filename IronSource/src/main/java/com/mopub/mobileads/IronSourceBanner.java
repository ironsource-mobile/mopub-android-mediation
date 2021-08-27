package com.mopub.mobileads;

import static com.mopub.common.DataKeys.ADM_KEY;
import static com.mopub.common.DataKeys.ADUNIT_FORMAT;
import static com.mopub.common.DataKeys.AD_HEIGHT;
import static com.mopub.common.DataKeys.AD_WIDTH;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CLICKED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CUSTOM;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_ATTEMPTED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_FAILED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_SUCCESS;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.WILL_LEAVE_APPLICATION;
import static com.mopub.mobileads.MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR;
import static com.mopub.mobileads.MoPubErrorCode.NETWORK_NO_FILL;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ironsource.mediationsdk.ISBannerSize;
import com.ironsource.mediationsdk.IronSource;
import com.ironsource.mediationsdk.IronSourceBannerLayout;
import com.ironsource.mediationsdk.logger.IronSourceError;
import com.ironsource.mediationsdk.sdk.BannerListener;
import com.mopub.common.LifecycleListener;
import com.mopub.common.MoPub;
import com.mopub.common.MoPubLifecycleManager;
import com.mopub.common.Preconditions;
import com.mopub.common.logging.MoPubLog;

import java.util.Map;

public class IronSourceBanner extends BaseAd implements BannerListener {
    private static final String APPLICATION_KEY = "applicationKey";
    private static final String INSTANCE_ID_KEY = "instanceId";
    private static final String ADAPTER_NAME = IronSourceBanner.class.getSimpleName();

    private IronSourceBannerLayout mBannerLayout;

    private String mInstanceId = IronSourceAdapterConfiguration.DEFAULT_INSTANCE_ID;

    @NonNull
    private final IronSourceAdapterConfiguration mIronSourceAdapterConfiguration;

    public IronSourceBanner() {
        mIronSourceAdapterConfiguration = new IronSourceAdapterConfiguration();
    }

    @NonNull
    @Override
    protected String getAdNetworkId() {
        return mInstanceId;
    }

    @Override
    protected boolean checkAndInitializeSdk(@NonNull Activity activity, @NonNull AdData adData) {
        Preconditions.checkNotNull(activity);
        Preconditions.checkNotNull(adData);

        final Map<String, String> extras = adData.getExtras();
        final String applicationKey = extras.get(APPLICATION_KEY);
        final Context context = activity.getApplicationContext();

        if (TextUtils.isEmpty(applicationKey) || context == null) {
            logAndFailAd(ADAPTER_CONFIGURATION_ERROR, "ironSource application key and/or Context " +
                    "might be null.", getAdNetworkId());
            return false;
        }

        try {
            IronSource.setConsent(MoPub.canCollectPersonalInformation());
            initIronSourceSDK(context, applicationKey, extras);

            return true;
        } catch (Exception e) {
            logAndFailAd(ADAPTER_CONFIGURATION_ERROR, e.getLocalizedMessage(), getAdNetworkId());

            return false;
        }
    }

    @Override
    protected void load(@NonNull final Context context, @NonNull final AdData adData) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(adData);

        if (!(context instanceof Activity)) {
            logAndFailAd(ADAPTER_CONFIGURATION_ERROR, "Context must be an instance of Activity.",
                    getAdNetworkId());
            return;
        }

        setAutomaticImpressionAndClickTracking(false);

        final Map<String, String> extras = adData.getExtras();
        final String instanceId = extras.get(INSTANCE_ID_KEY);
        final String adUnitFormat = extras.get(ADUNIT_FORMAT);
        final String adMarkup = extras.get(ADM_KEY);

        if (TextUtils.isEmpty(adMarkup)) {
            logAndFailAd(NETWORK_NO_FILL, "ironSource Advanced Bidding ad markup not available. " +
                    "Aborting the ad request.", getAdNetworkId());
            return;
        }

        if (!TextUtils.isEmpty(instanceId)) {
            mInstanceId = instanceId;
        }

        boolean isBannerFormat = false;
        if (!TextUtils.isEmpty(adUnitFormat)) {
            isBannerFormat = (adUnitFormat.toLowerCase()).equals("banner");
        }

        if (!isBannerFormat) {
            logAndFailAd(ADAPTER_CONFIGURATION_ERROR, "The requested ad format must be of " +
                    "type banner.", getAdNetworkId());
            return;
        }

        mIronSourceAdapterConfiguration.retainIronSourceAdUnitsToInitPrefsIfNecessary(context, extras);
        mIronSourceAdapterConfiguration.setCachedInitializationParameters(context, extras);

        MoPubLifecycleManager.getInstance((Activity) context).addLifecycleListener(lifecycleListener);

        if (mBannerLayout != null) {
            IronSource.destroyBanner(mBannerLayout);
        }

        mBannerLayout = createBannerLayout((Activity) context, extras);
        mBannerLayout.setBannerListener(this);

        IronSource.loadISDemandOnlyBannerWithAdm(((Activity) context), mBannerLayout, mInstanceId, adMarkup);
        MoPubLog.log(getAdNetworkId(), LOAD_ATTEMPTED, ADAPTER_NAME);
    }

    @Nullable
    @Override
    protected View getAdView() {
        return mBannerLayout;
    }

    @Override
    protected void onInvalidate() {
        if (mBannerLayout != null) {
            IronSource.destroyBanner(mBannerLayout);
            mBannerLayout = null;

            MoPubLog.log(getAdNetworkId(), CUSTOM, ADAPTER_NAME, "Invalidating ironSource banner.");
        }
    }

    @Nullable
    @Override
    protected LifecycleListener getLifecycleListener() {
        return null;
    }

    private void initIronSourceSDK(Context context, String appKey, Map<String, String> extras) {
        MoPubLog.log(getAdNetworkId(), CUSTOM, ADAPTER_NAME, "ironSource banner initialization " +
                "is called with application key: " + appKey);
        IronSource.AD_UNIT[] adUnitsToInit = mIronSourceAdapterConfiguration.getIronSourceAdUnitsToInitList(context, extras);
        IronSourceAdapterConfiguration.initIronSourceSDK(context, appKey, adUnitsToInit);
    }

    private int getAdHeight(Map<String, String> extras) {
        final String heightValue = extras.get(AD_HEIGHT);

        if (heightValue != null) {
            return Integer.parseInt(heightValue);
        }

        return 0;
    }

    private int getAdWidth(Map<String, String> extras) {
        final String widthValue = extras.get(AD_WIDTH);

        if (widthValue != null) {
            return Integer.parseInt(widthValue);
        }

        return 0;
    }

    private IronSourceBannerLayout createBannerLayout(Activity activity, Map<String, String> extras) {
        final int adWidth = getAdWidth(extras);
        final int adHeight = getAdHeight(extras);

        ISBannerSize bannerSize;
        if (adHeight > 0 && adWidth > 0) {
            bannerSize = new ISBannerSize(adWidth, adHeight);
        } else {
            bannerSize = ISBannerSize.BANNER;
        }

        return IronSource.createBanner(activity, bannerSize);
    }

    private void logAndFailAd(final MoPubErrorCode errorCode, final String errorMsg,
                              final String instanceId) {
        MoPubLog.log(instanceId, LOAD_FAILED, ADAPTER_NAME, errorCode.getIntCode(), errorCode);
        MoPubLog.log(instanceId, CUSTOM, ADAPTER_NAME, "Failed to request ironSource banner. " + errorMsg);

        if (mLoadListener != null) {
            mLoadListener.onAdLoadFailed(errorCode);
        }
    }

    @Override
    public void onBannerAdLoaded() {
        MoPubLog.log(CUSTOM, ADAPTER_NAME, LOAD_SUCCESS);

        if (mLoadListener != null) {
            mLoadListener.onAdLoaded();
        }
    }

    @Override
    public void onBannerAdLoadFailed(IronSourceError ironSourceError) {
        logAndFailAd(IronSourceAdapterConfiguration.convertISNBannerErrorToMoPubError(ironSourceError),
                ironSourceError.getErrorMessage(), mInstanceId);
    }

    @Override
    public void onBannerAdClicked() {
        MoPubLog.log(mInstanceId, CLICKED, ADAPTER_NAME);

        if (mInteractionListener != null) {
            mInteractionListener.onAdClicked();
        }
    }

    @Override
    public void onBannerAdScreenPresented() {
        if (mInteractionListener != null) {
            mInteractionListener.onAdImpression();
        }
    }

    @Override
    public void onBannerAdScreenDismissed() {
        if (mInteractionListener != null) {
            mInteractionListener.onAdDismissed();
        }
    }

    @Override
    public void onBannerAdLeftApplication() {
        MoPubLog.log(CUSTOM, ADAPTER_NAME, WILL_LEAVE_APPLICATION);
    }

    private static final LifecycleListener lifecycleListener = new LifecycleListener() {

        @Override
        public void onCreate(@NonNull Activity activity) {
        }

        @Override
        public void onStart(@NonNull Activity activity) {
        }

        @Override
        public void onPause(@NonNull Activity activity) {
            IronSource.onPause(activity);
        }

        @Override
        public void onResume(@NonNull Activity activity) {
            IronSource.onResume(activity);
        }

        @Override
        public void onRestart(@NonNull Activity activity) {
        }

        @Override
        public void onStop(@NonNull Activity activity) {
        }

        @Override
        public void onDestroy(@NonNull Activity activity) {
        }

        @Override
        public void onBackPressed(@NonNull Activity activity) {
        }
    };
}
