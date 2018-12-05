package org.linphone;

/*
LinphoneLauncherActivity.java
Copyright (C) 2017  Belledonne Communications, Grenoble, France

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

import static android.content.Intent.ACTION_MAIN;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import org.linphone.assistant.RemoteProvisioningActivity;
import org.linphone.call.CallActivity;
import org.linphone.contacts.ContactsManager;
import org.linphone.settings.LinphonePreferences;
import org.linphone.utils.FileUtils;

/** Launch Linphone main activity when Service is ready. */
public class LinphoneLauncherActivity extends Activity {

    private final String ACTION_CALL_LINPHONE = "org.linphone.intent.action.CallLaunched";

    private Handler mHandler;
    private ServiceWaitThread mServiceThread;
    private String mAddressToCall;
    private Uri mUriToResolve;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hack to avoid to draw twice LinphoneActivity on tablets
        if (getResources().getBoolean(R.bool.orientation_portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        setContentView(R.layout.launch_screen);

        mHandler = new Handler();

        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_CALL.equals(action)) {
                if (intent.getData() != null) {
                    mAddressToCall = intent.getData().toString();
                    mAddressToCall = mAddressToCall.replace("%40", "@");
                    mAddressToCall = mAddressToCall.replace("%3A", ":");
                    if (mAddressToCall.startsWith("sip:")) {
                        mAddressToCall = mAddressToCall.substring("sip:".length());
                    } else if (mAddressToCall.startsWith("tel:")) {
                        mAddressToCall = mAddressToCall.substring("tel:".length());
                    }
                }
            } else if (Intent.ACTION_VIEW.equals(action)) {
                if (LinphoneService.isReady()) {
                    mAddressToCall =
                            ContactsManager.getAddressOrNumberForAndroidContact(
                                    getContentResolver(), intent.getData());
                } else {
                    mUriToResolve = intent.getData();
                }
            }
        }

        if (LinphoneService.isReady()) {
            onServiceReady();
        } else {
            // start linphone as background
            startService(new Intent(ACTION_MAIN).setClass(this, LinphoneService.class));
            mServiceThread = new ServiceWaitThread();
            mServiceThread.start();
        }
    }

    private void onServiceReady() {
        final Class<? extends Activity> classToStart;
        /*if (getResources().getBoolean(R.bool.show_tutorials_instead_of_app)) {
        	classToStart = TutorialLauncherActivity.class;
        } else */
        if (getResources().getBoolean(R.bool.display_sms_remote_provisioning_activity)
                && LinphonePreferences.instance().isFirstRemoteProvisioning()) {
            classToStart = RemoteProvisioningActivity.class;
        } else {
            classToStart = LinphoneActivity.class;
        }

        mHandler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        Intent newIntent = new Intent(LinphoneLauncherActivity.this, classToStart);
                        Intent intent = getIntent();
                        String stringFileShared = null;
                        String stringUriFileShared = null;
                        Uri fileUri = null;
                        if (intent != null) {
                            String action = intent.getAction();
                            String type = intent.getType();
                            newIntent.setData(intent.getData());
                            if (Intent.ACTION_SEND.equals(action) && type != null) {
                                if (("text/plain").equals(type)
                                        && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                                    stringFileShared = intent.getStringExtra(Intent.EXTRA_TEXT);
                                    newIntent.putExtra("msgShared", stringFileShared);
                                } else {
                                    fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                                    stringUriFileShared =
                                            FileUtils.getFilePath(getBaseContext(), fileUri);
                                    newIntent.putExtra("fileShared", stringUriFileShared);
                                }
                            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
                                if (type.startsWith("image/")) {
                                    // TODO : Manage multiple files sharing
                                }
                            } else if (ACTION_CALL_LINPHONE.equals(action)
                                    && (intent.getStringExtra("NumberToCall") != null)) {
                                String numberToCall = intent.getStringExtra("NumberToCall");
                                if (CallActivity.isInstanciated()) {
                                    CallActivity.instance().startIncomingCallActivity();
                                } else {
                                    LinphoneManager.getInstance()
                                            .newOutgoingCall(numberToCall, null);
                                }
                            }
                        }
                        if (mUriToResolve != null) {
                            mAddressToCall =
                                    ContactsManager.getAddressOrNumberForAndroidContact(
                                            getContentResolver(), mUriToResolve);
                            Log.i(
                                    "LinphoneLauncher",
                                    "Intent has uri to resolve : " + mUriToResolve.toString());
                            mUriToResolve = null;
                        }
                        if (mAddressToCall != null) {
                            newIntent.putExtra("SipUriOrNumber", mAddressToCall);
                            Log.i(
                                    "LinphoneLauncher",
                                    "Intent has address to call : " + mAddressToCall);
                            mAddressToCall = null;
                        }
                        startActivity(newIntent);
                        if (classToStart == LinphoneActivity.class
                                && LinphoneActivity.isInstanciated()
                                && (stringFileShared != null || fileUri != null)) {
                            if (stringFileShared != null) {
                                LinphoneActivity.instance()
                                        .displayChat(null, stringFileShared, null);
                            } else if (fileUri != null) {
                                LinphoneActivity.instance()
                                        .displayChat(null, null, stringUriFileShared);
                            }
                        }
                    }
                },
                1000);
    }

    private class ServiceWaitThread extends Thread {
        public void run() {
            while (!LinphoneService.isReady()) {
                try {
                    sleep(30);
                } catch (InterruptedException e) {
                    throw new RuntimeException("waiting thread sleep() has been interrupted");
                }
            }
            mHandler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            onServiceReady();
                        }
                    });
            mServiceThread = null;
        }
    }
}
