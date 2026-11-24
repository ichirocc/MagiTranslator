/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;

import javax.annotation.Nullable;

import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.ErrorCodes;


public abstract class GeneralActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 16 (targetSdk 36) enforces predictive back: Activity.onBackPressed() overrides are no
        // longer called. Route the system back gesture to them through the dispatcher. The callback is
        // disabled while running so that super.onBackPressed() falls through to the default behaviour.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                try {
                    GeneralActivity.this.onBackPressed();
                } finally {
                    setEnabled(true);
                }
            }
        });
        // if the models are not loaded and an activity different than LoadingActivity or AccessActivity is created,
        // that means that the system has killed the app, and now it is launching the app with the last activity (restoring it).
        // In this case we start the loading activity instead, otherwise the app will crash for the lack of loaded models.
        // After the loading of the models, the app will handle itself the restore of the latest opened fragment (using the "fragment" key in sharedPreferences).
        boolean isLoadingActivity = this instanceof LoadingActivity;
        boolean isAccessActivity = this instanceof AccessActivity;
        if(!isLoadingActivity && !isAccessActivity && !((Global) getApplication()).areModelsLoaded()){
            //start LoadingActivity
            Intent intent = new Intent(this, LoadingActivity.class);
            intent.putExtra("activity", "general");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }
    }

    public void showMissingGoogleTTSDialog(@Nullable DialogInterface.OnClickListener continueListener) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.error_missing_tts);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"));
                intent.setPackage("com.android.vending");
                try {
                    startActivity(intent);
                }catch (Exception e){
                    e.printStackTrace();
                    finish();
                }
            }
        });
        if(continueListener != null){
            builder.setNegativeButton(R.string.continue_without_tts, continueListener);
        }
        builder.create().show();
    }

    public void showGoogleTTSErrorDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.error_tts);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.create().show();
    }

    public void showGoogleTTSErrorDialog(DialogInterface.OnClickListener continueListener) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.error_tts);
        builder.setPositiveButton(R.string.continue_without_tts, continueListener);
        builder.setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        builder.create().show();
    }

    public void showInternetLackDialog(int message, DialogInterface.OnClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, listener);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public void showApiKeyFileErrorDialog(int message, DialogInterface.OnClickListener confirmListener, DialogInterface.OnClickListener cancelListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, confirmListener);
        builder.setNegativeButton(R.string.exit, cancelListener);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public void showConfirmDeleteDialog(DialogInterface.OnClickListener confirmListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setMessage(R.string.dialog_confirm_delete);
        builder.setPositiveButton(android.R.string.ok, confirmListener);
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.create().show();
    }

    protected void showConfirmExitDialog(DialogInterface.OnClickListener confirmListener) {
        //creazione del dialog.
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setMessage(R.string.dialog_confirm_exit);
        builder.setPositiveButton(android.R.string.ok, confirmListener);
        builder.setNegativeButton(android.R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private AlertDialog missingMadladDialog;

    //MagiTranslator (SPEC 19.9): Nepali needs Madlad; guide the user to the Models Manager instead of failing silently
    public void showMissingMadladDialog() {
        if (isFinishing() || (missingMadladDialog != null && missingMadladDialog.isShowing())) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setTitle(R.string.missing_madlad_nepali_title);
        builder.setMessage(R.string.missing_madlad_nepali_message);
        builder.setPositiveButton(R.string.open_models_manager, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent intent = new Intent(GeneralActivity.this, SettingsActivity.class);
                intent.putExtra("startWithModelManager", true);
                startActivity(intent);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        missingMadladDialog = builder.create();
        missingMadladDialog.show();
    }

    public void onError(int reason, long value) {
        if (reason == ErrorCodes.ERROR_MISSING_MADLAD) {
            showMissingMadladDialog();
            return;
        }
        /*switch (reason) {
            case ErrorCodes.MISSING_PLAY_SERVICES: {
                GoogleApiAvailability.getInstance().getErrorDialog(this, (int) value, 34).show();
                break;
            }
        }*/
    }
}
