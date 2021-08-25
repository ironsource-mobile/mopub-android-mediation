package com.mopub.mobileads;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import com.ironsource.mediationsdk.ISBannerSize;
import com.ironsource.mediationsdk.IronSource;
import com.ironsource.mediationsdk.IronSourceBannerLayout;
import com.ironsource.mediationsdk.logger.IronSourceError;
import com.ironsource.mediationsdk.sdk.BannerListener;
import com.mopub.common.DataKeys;
import com.mopub.common.LifecycleListener;
import com.mopub.common.MoPub;
import com.mopub.common.MoPubLifecycleManager;
import com.mopub.common.Preconditions;
import com.mopub.common.logging.MoPubLog;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.mopub.common.DataKeys.ADUNIT_FORMAT;
import static com.mopub.common.DataKeys.AD_HEIGHT;
import static com.mopub.common.DataKeys.AD_WIDTH;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CLICKED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CUSTOM;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CUSTOM_WITH_THROWABLE;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_ATTEMPTED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_FAILED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_SUCCESS;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.WILL_LEAVE_APPLICATION;

/**
 * This class is the Adapter allows ironSource SDK to serve Banner ads under MoPub mediation.
 * Once a new ad is being loaded, it will create the proper banner layout and load a new ad
 * according to the ADM parameter.
 * In case that parameter is missing - the adapter will report adLoadFailed (supports only bidding flow)
 */
public class IronSourceBanner extends BaseAd implements BannerListener {

    // Configuration keys
    private static final String APPLICATION_KEY = "applicationKey";
    private static final String INSTANCE_ID_KEY = "instanceId";
    private static final String ADAPTER_NAME = IronSourceBanner.class.getSimpleName();

    // Network identifier of ironSource
    private String mInstanceId = IronSourceAdapterConfiguration.DEFAULT_INSTANCE_ID;

    // The presenting banner view
    private IronSourceBannerLayout mBannerLayout;

    @NonNull
    private final IronSourceAdapterConfiguration mIronSourceAdapterConfiguration;

    @SuppressWarnings("unused")
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

        boolean canCollectPersonalInfo = MoPub.canCollectPersonalInformation();
        IronSource.setConsent(canCollectPersonalInfo);

        final Map<String, String> extras = adData.getExtras();

        try {
            if (TextUtils.isEmpty(extras.get(APPLICATION_KEY))) {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "ironSource Banner failed to initialize. " +
                        "ironSource applicationKey is not valid. Please make sure it's entered properly on MoPub UI.");

                if (mLoadListener != null) {
                    mLoadListener.onAdLoadFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
                }

                return false;
            }

            final String applicationKey = extras.get(APPLICATION_KEY);
            final Context context = activity.getApplicationContext();

            if (context != null) {
                initIronSourceSDK(context, applicationKey, extras);
                return true;
            } else {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "ironSource Interstitial failed to initialize." +
                        "Application Context obtained by Activity launching this interstitial is null.");
                if (mLoadListener != null) {
                    mLoadListener.onAdLoadFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
                }
                return false;
            }

        } catch (Exception e) {
            MoPubLog.log(CUSTOM_WITH_THROWABLE, e);
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "ironSource Banner failed to initialize." +
                    "Ensure ironSource applicationKey and instanceId are properly entered on MoPub UI.");
            if (mLoadListener != null) {
                mLoadListener.onAdLoadFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            }

            return false;
        }
    }

    @Override
    protected void load(@NonNull final Context context, @NonNull final AdData adData) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(adData);

        setAutomaticImpressionAndClickTracking(false);

        if (!(context instanceof Activity)) {
            MoPubLog.log(ADAPTER_NAME, LOAD_FAILED, ADAPTER_NAME, MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR.getIntCode(), MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);

            if (mLoadListener != null) {
                mLoadListener.onAdLoadFailed(MoPubErrorCode.INTERNAL_ERROR);
            }

            return;
        }

        final Map<String, String> extras = adData.getExtras();
        final String instanceId = extras.get(INSTANCE_ID_KEY);
        if (!TextUtils.isEmpty(instanceId)) {
            mInstanceId = instanceId;
        }

        if (extras.isEmpty()) {
            MoPubLog.log(ADAPTER_NAME, CUSTOM, mInstanceId, "missing ad data");

            if (mLoadListener != null) {
                mLoadListener.onAdLoadFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            }

            return;
        }

        String adUnitFormat = extras.get(ADUNIT_FORMAT);
        if (!TextUtils.isEmpty(adUnitFormat)) {
            adUnitFormat = adUnitFormat.toLowerCase();
        }

        final boolean isBannerFormat = "banner".equals(adUnitFormat);
        if (!isBannerFormat) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Please ensure your MoPub adunit's format is Banner.");
            MoPubLog.log(getAdNetworkId(), LOAD_FAILED, ADAPTER_NAME,
                    MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR.getIntCode(),
                    MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);

            if (mLoadListener != null) {
                mLoadListener.onAdLoadFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            }

            return;
        }

        MoPubLog.log(CUSTOM, ADAPTER_NAME, "IronSource extras: " + extras.toString());

        mIronSourceAdapterConfiguration.retainIronSourceAdUnitsToInitPrefsIfNecessary(context,extras);
        mIronSourceAdapterConfiguration.setCachedInitializationParameters(context, extras);
        MoPubLifecycleManager.getInstance((Activity) context).addLifecycleListener(lifecycleListener);
        MoPubLog.log(getAdNetworkId(), LOAD_ATTEMPTED, ADAPTER_NAME);

        final String adMarkup = extras.get(DataKeys.ADM_KEY);

        if (TextUtils.isEmpty(adMarkup)) {
            MoPubLog.log(CUSTOM, ADAPTER_NAME, "Advanced Bidding ad markup not available. Aborting the ad request");
            if (mLoadListener != null) {
                mLoadListener.onAdLoadFailed(MoPubErrorCode.NETWORK_NO_FILL);
            }
            return;
        }

        if (mBannerLayout != null) {
            IronSource.destroyBanner(mBannerLayout);
        }

        mBannerLayout = createBannerLayout((Activity)context, extras);
        mBannerLayout.setBannerListener(this);

        IronSource.loadISDemandOnlyBannerWithAdm(((Activity) context), mBannerLayout, mInstanceId, adMarkup);
    }

    @Nullable
    @Override
    protected View getAdView() {
        return mBannerLayout;
    }

    /**
     * Banner Callbacks
     */
    @Override
    protected void onInvalidate() {
        if (mBannerLayout != null) {
            IronSource.destroyBanner(mBannerLayout);
            mBannerLayout = null;
        }
    }

    @Nullable
    @Override
    protected LifecycleListener getLifecycleListener() {
        return null;
    }

    private void initIronSourceSDK(Context context, String appKey, Map<String, String> extras) {
        MoPubLog.log(getAdNetworkId(), CUSTOM, ADAPTER_NAME, "ironSource Banner initialization is called with applicationKey: " + appKey);
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
        ISBannerSize bannerSize = ISBannerSize.BANNER;

        //when no size defined - create standard banner size (320x50) - make sure it is OK
        int adWidth = getAdWidth(extras);
        int adHeight = getAdHeight(extras);
        if (adHeight > 0 && adWidth > 0) {
            bannerSize = new ISBannerSize(adWidth, adHeight);
        }

        return IronSource.createBanner (
                activity,
                bannerSize
        );
    }

    private void logAndFailAd(final MoPubErrorCode errorCode, final String instanceId) {
        MoPubLog.log(instanceId, LOAD_FAILED, ADAPTER_NAME,
                errorCode.getIntCode(),
                errorCode);

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
        logAndFailAd(IronSourceAdapterConfiguration.convertISNBannerErrorToMoPubError(ironSourceError), mInstanceId);
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
        MoPubLog.log(CUSTOM, ADAPTER_NAME, "IronSource onBannerAdScreenPresented()");

        if (mInteractionListener != null) {
            mInteractionListener.onAdImpression();
        }
    }

    @Override
    public void onBannerAdScreenDismissed() {
        MoPubLog.log(CUSTOM, ADAPTER_NAME, "IronSource onBannerAdScreenDismissed()");

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