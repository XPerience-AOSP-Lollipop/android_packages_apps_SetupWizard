/*
 * Copyright (C) 2013 The CyanogenMod Project
 * Copyright (C) 2017 The XPerience Project
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

package mx.xperience.setupwizard.setup;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.NumberPicker;
import android.widget.Toast;

import com.android.internal.telephony.MccTable;
import mx.xperience.setupwizard.R;
import mx.xperience.setupwizard.SetupWizardApp;
import mx.xperience.setupwizard.xpestats.SetupStats;
import mx.xperience.setupwizard.ui.LocalePicker;
import mx.xperience.setupwizard.ui.SetupPageFragment;
import mx.xperience.setupwizard.util.SetupWizardUtils;

import java.util.List;
import java.util.Locale;

public class LocalePage extends SetupPage {

    public static final String TAG = "LocalePage";

    private LocaleFragment mLocaleFragment;

    public LocalePage(Context context, SetupDataCallbacks callbacks) {
        super(context, callbacks);
    }

    @Override
    public Fragment getFragment(FragmentManager fragmentManager, int action) {
        mLocaleFragment = (LocaleFragment)fragmentManager.findFragmentByTag(getKey());
        if (mLocaleFragment == null) {
            Bundle args = new Bundle();
            args.putString(Page.KEY_PAGE_ARGUMENT, getKey());
            args.putInt(Page.KEY_PAGE_ACTION, action);
            mLocaleFragment = new LocaleFragment();
            mLocaleFragment.setArguments(args);
        }
        return mLocaleFragment;
    }

    @Override
    public int getTitleResId() {
        return R.string.setup_locale;
    }

    @Override
    public int getIconResId() {
        return R.drawable.ic_locale;
    }

    @Override
    public boolean doNextAction() {
        if (isLocked()) {
            confirmCyanogenCredentials(mLocaleFragment);
            return true;
        } else {
            if (mLocaleFragment != null) {
                mLocaleFragment.sendLocaleStats();
            }
            return super.doNextAction();
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SetupWizardApp.REQUEST_CODE_UNLOCK) {
            if (resultCode == Activity.RESULT_OK) {
                ((SetupWizardApp) mContext.getApplicationContext()).setIsAuthorized(true);
                getCallbacks().onNextPage();
                return true;
            }
        }
        return false;
    }

    @Override
    public String getKey() {
        return TAG;
    }

    @Override
    public int getNextButtonTitleResId() {
        if (isLocked()) {
            return R.string.setup_unlock;
        } else {
            return R.string.next;
        }
    }

    private void confirmCyanogenCredentials(final Fragment fragment) {
        AccountManager accountManager = AccountManager.get(mContext);
        accountManager.editProperties(SetupWizardApp.ACCOUNT_TYPE_CYANOGEN, null,
                new AccountManagerCallback<Bundle>() {
                    public void run(AccountManagerFuture<Bundle> f) {
                        try {
                            Bundle b = f.getResult();
                            Intent i = b.getParcelable(AccountManager.KEY_INTENT);
                            i.putExtra(SetupWizardApp.EXTRA_FIRST_RUN, true);
                            i.putExtra(SetupWizardApp.EXTRA_SHOW_BUTTON_BAR, true);
                            i.putExtra(SetupWizardApp.EXTRA_USE_IMMERSIVE, true);
                            i.putExtra(SetupWizardApp.EXTRA_LOGIN_FOR_KILL_SWITCH, true);
                            fragment.startActivityForResult(i,
                                    SetupWizardApp.REQUEST_CODE_UNLOCK);
                        } catch (Throwable t) {
                            Log.e(getKey(), "confirmCredentials failed", t);
                        }
                    }
                }, null);
    }

    private boolean isLocked() {
        boolean isAuthorized = ((SetupWizardApp) mContext.getApplicationContext()).isAuthorized();
        if (SetupWizardUtils.isDeviceLocked()) {
            return !isAuthorized;
        }
        return false;
    }

    public void simChanged() {
        if (mLocaleFragment != null) {
            mLocaleFragment.fetchAndUpdateSimLocale();
        }
    }

    public static class LocaleFragment extends SetupPageFragment {

        private ArrayAdapter<com.android.internal.app.LocalePicker.LocaleInfo> mLocaleAdapter;
        private Locale mInitialLocale;
        private Locale mCurrentLocale;
        private int[] mAdapterIndices;
        private boolean mIgnoreSimLocale;
        private LocalePicker mLanguagePicker;
        private FetchUpdateSimLocaleTask mFetchUpdateSimLocaleTask;
        private final Handler mHandler = new Handler();
        private boolean mPendingLocaleUpdate;
        private boolean mPaused = true;

        private final Runnable mUpdateLocale = new Runnable() {
            public void run() {
                if (mCurrentLocale != null) {
                    mLanguagePicker.setEnabled(false);
                    com.android.internal.app.LocalePicker.updateLocale(mCurrentLocale);
                }
            }
        };

        @Override
        protected void initializePage() {
            mLanguagePicker = (LocalePicker) mRootView.findViewById(R.id.locale_list);
            loadLanguages();
        }

        private void loadLanguages() {
            mLocaleAdapter = com.android.internal.app.LocalePicker.constructAdapter(getActivity(),
                    R.layout.locale_picker_item, R.id.locale);
            mCurrentLocale = mInitialLocale = Locale.getDefault();
            fetchAndUpdateSimLocale();
            mAdapterIndices = new int[mLocaleAdapter.getCount()];
            int currentLocaleIndex = 0;
            String [] labels = new String[mLocaleAdapter.getCount()];
            for (int i=0; i<mAdapterIndices.length; i++) {
                com.android.internal.app.LocalePicker.LocaleInfo localLocaleInfo =
                        mLocaleAdapter.getItem(i);
                Locale localLocale = localLocaleInfo.getLocale();
                if (localLocale.equals(mCurrentLocale)) {
                    currentLocaleIndex = i;
                }
                mAdapterIndices[i] = i;
                labels[i] = localLocaleInfo.getLabel();
            }
            mLanguagePicker.setDisplayedValues(labels);
            mLanguagePicker.setMaxValue(labels.length - 1);
            mLanguagePicker.setValue(currentLocaleIndex);
            mLanguagePicker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
            mLanguagePicker.setOnValueChangedListener(new LocalePicker.OnValueChangeListener() {
                public void onValueChange(LocalePicker picker, int oldVal, int newVal) {
                    setLocaleFromPicker();
                }
            });
            mLanguagePicker.setOnScrollListener(new LocalePicker.OnScrollListener() {
                @Override
                public void onScrollStateChange(LocalePicker view, int scrollState) {
                    if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                        mIgnoreSimLocale = true;
                    }
                }
            });
        }

        private void setLocaleFromPicker() {
            mIgnoreSimLocale = true;
            int i = mAdapterIndices[mLanguagePicker.getValue()];
            final com.android.internal.app.LocalePicker.LocaleInfo localLocaleInfo = mLocaleAdapter.getItem(i);
            onLocaleChanged(localLocaleInfo.getLocale());
        }

        private void onLocaleChanged(Locale paramLocale) {
            mLanguagePicker.setEnabled(true);
            Resources localResources = getActivity().getResources();
            Configuration localConfiguration1 = localResources.getConfiguration();
            Configuration localConfiguration2 = new Configuration();
            localConfiguration2.locale = paramLocale;
            localResources.updateConfiguration(localConfiguration2, null);
            localResources.updateConfiguration(localConfiguration1, null);
            mHandler.removeCallbacks(mUpdateLocale);
            mCurrentLocale = paramLocale;
            mHandler.postDelayed(mUpdateLocale, 1000);
        }

        @Override
        protected int getLayoutResource() {
            return R.layout.setup_locale;
        }

        public void sendLocaleStats() {
            if (!mCurrentLocale.equals(mInitialLocale)) {
                SetupStats.addEvent(SetupStats.Categories.SETTING_CHANGED,
                        SetupStats.Action.CHANGE_LOCALE, SetupStats.Label.LOCALE,
                        mCurrentLocale.getDisplayName());
            }
        }

        public void fetchAndUpdateSimLocale() {
            if (mIgnoreSimLocale || isDetached()) {
                return;
            }
            if (mPaused) {
                mPendingLocaleUpdate = true;
                return;
            }
            if (mFetchUpdateSimLocaleTask != null) {
                mFetchUpdateSimLocaleTask.cancel(true);
            }
            mFetchUpdateSimLocaleTask = new FetchUpdateSimLocaleTask();
            mFetchUpdateSimLocaleTask.execute();
        }

        private class FetchUpdateSimLocaleTask extends AsyncTask<Void, Void, Locale> {
            @Override
            protected Locale doInBackground(Void... params) {
                Locale locale = null;
                Activity activity = getActivity();
                if (activity != null) {
                    // If the sim is currently pin locked, return
                    TelephonyManager telephonyManager = (TelephonyManager)
                            activity.getSystemService(Context.TELEPHONY_SERVICE);
                    int state = telephonyManager.getSimState();
                    if(state == TelephonyManager.SIM_STATE_PIN_REQUIRED ||
                            state == TelephonyManager.SIM_STATE_PUK_REQUIRED) {
                        return null;
                    }

                    final SubscriptionManager subscriptionManager =
                            SubscriptionManager.from(activity);
                    List<SubscriptionInfo> activeSubs =
                            subscriptionManager.getActiveSubscriptionInfoList();
                    if (activeSubs == null || activeSubs.isEmpty()) {
                        return null;
                    }

                    // Fetch locale for active sim's MCC
                    int mcc = activeSubs.get(0).getMcc();
                    locale = MccTable.getLocaleFromMcc(activity, mcc, null);

                    // If that fails, fall back to preferred languages reported
                    // by the sim
                    if (locale == null) {
                        String localeString = telephonyManager.getLocaleFromDefaultSim();
                        if (localeString != null) {
                            locale = Locale.forLanguageTag(localeString);

                        }
                    }
                }
                return locale;
            }

            @Override
            protected void onPostExecute(Locale simLocale) {
                if (simLocale != null && !simLocale.equals(mCurrentLocale)) {
                    if (!mIgnoreSimLocale && !isDetached()) {
                        String label = getString(R.string.sim_locale_changed,
                                simLocale.getDisplayName());
                        Toast.makeText(getActivity(), label, Toast.LENGTH_SHORT).show();
                        onLocaleChanged(simLocale);
                        mIgnoreSimLocale = true;
                    }
                }
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            mPaused = true;
        }

        @Override
        public void onResume() {
            super.onResume();
            mPaused = false;
            if (mLanguagePicker != null) {
                mLanguagePicker.setEnabled(true);
            }
            if (mPendingLocaleUpdate) {
                mPendingLocaleUpdate = false;
                fetchAndUpdateSimLocale();
            }
        }
    }

}