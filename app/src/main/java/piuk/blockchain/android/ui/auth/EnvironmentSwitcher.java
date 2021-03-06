package piuk.blockchain.android.ui.auth;

import android.content.Context;
import android.support.v7.app.AlertDialog;
import android.widget.ArrayAdapter;

import info.blockchain.wallet.api.PersistentUrls;

import java.util.Arrays;
import java.util.List;

import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.api.DebugSettings;
import piuk.blockchain.android.ui.account.AccountViewModel;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.util.AppRate;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.PrefsUtil;

class EnvironmentSwitcher {

    private Context context;
    private PrefsUtil prefsUtil;
    private DebugSettings debugSettings;
    private AppUtil appUtil;

    EnvironmentSwitcher(Context context, DebugSettings debugSettings, AppUtil appUtil, PrefsUtil prefsUtil) {
        this.context = context;
        this.prefsUtil = prefsUtil;
        this.debugSettings = debugSettings;
        this.appUtil = appUtil;
    }

    void showEnvironmentSelectionDialog() {
        List<String> itemsList = Arrays.asList("Production", "Staging", "Dev", "TestNet");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, R.layout.item_environment_list, itemsList);

        PersistentUrls.Environment environment = debugSettings.getCurrentEnvironment();
        int selection;
        switch (environment) {
            case STAGING:
                selection = 1;
                break;
            case DEV:
                selection = 2;
                break;
            case TESTNET:
                selection = 3;
                break;
            default:
                selection = 0;
                break;
        }

        final PersistentUrls.Environment[] selectedEnvironment = new PersistentUrls.Environment[1];

        new AlertDialog.Builder(context, R.style.AlertDialogStyle)
                .setTitle("Choose Environment")
                .setSingleChoiceItems(adapter, selection, (dialogInterface, i) -> {
                    switch (i) {
                        case 1:
                            selectedEnvironment[0] = PersistentUrls.Environment.STAGING;
                            break;
                        case 2:
                            selectedEnvironment[0] = PersistentUrls.Environment.DEV;
                            break;
                        case 3:
                            selectedEnvironment[0] = PersistentUrls.Environment.TESTNET;
                            break;
                        default:
                            selectedEnvironment[0] = PersistentUrls.Environment.PRODUCTION;
                            break;
                    }
                })
                .setPositiveButton("Select", (dialog, id) ->
                        new AlertDialog.Builder(context, R.style.AlertDialogStyle)
                                .setTitle("Warning")
                                .setMessage("The app will now restart. For the changes to take effect, " +
                                        "please remove the app from memory (by swiping the app out of " +
                                        "recents) otherwise you'll remain on your previously selected " +
                                        "environment - even if the app tells you otherwise!")
                                .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                                    debugSettings.changeEnvironment(
                                            selectedEnvironment[0] != null ? selectedEnvironment[0] : PersistentUrls.Environment.PRODUCTION);

                                    ToastCustom.makeText(
                                            context,
                                            "Environment set to " + debugSettings.getCurrentEnvironment().getName(),
                                            ToastCustom.LENGTH_SHORT,
                                            ToastCustom.TYPE_OK);

                                    resetAllTimers();
                                    appUtil.clearCredentialsAndKeepEnvironment();
                                })
                                .create()
                                .show())
                .setNegativeButton("Reset Timers", (dialogInterface, i) -> resetAllTimers())
                .create()
                .show();
    }

    private void resetAllTimers() {
        prefsUtil.removeValue(PrefsUtil.KEY_PIN_FAILS);
        prefsUtil.removeValue(PrefsUtil.KEY_FIRST_RUN);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_TIME_ELAPSED);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_BACKUP_NEVER);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_TWO_FA_NEVER);
        prefsUtil.removeValue(AccountViewModel.KEY_WARN_TRANSFER_ALL);
        prefsUtil.removeValue(PrefsUtil.KEY_SURVEY_COMPLETED);
        prefsUtil.removeValue(PrefsUtil.KEY_SURVEY_VISITS);
        prefsUtil.removeValue(PrefsUtil.KEY_APP_VISITS);
        prefsUtil.removeValue(PrefsUtil.KEY_ONBOARDING_COMPLETE);

        AppRate.reset(context);
        AccessState.getInstance().setPIN(null);

        ToastCustom.makeText(context, "Timers reset", ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);
    }

}
