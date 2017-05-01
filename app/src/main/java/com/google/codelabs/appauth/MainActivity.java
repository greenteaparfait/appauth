// Copyright 2016 Google Inc.
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//      http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.codelabs.appauth;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.UserManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatButton;
import android.support.v7.widget.AppCompatTextView;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.TokenResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.google.codelabs.appauth.MainApplication.LOG_TAG;

public class MainActivity extends AppCompatActivity {

  private static final String SHARED_PREFERENCES_NAME = "AuthStatePreference";
  private static final String AUTH_STATE = "AUTH_STATE";
  private static final String USED_INTENT = "USED_INTENT";
  private final static String LOGIN_HINT = "login_hint";

  static int leasePeriod = 60;

  MainApplication mMainApplication;

  // state
  AuthState mAuthState;

  // views
  AppCompatButton mAuthorize;
  AppCompatButton mMakeApiCall;
  AppCompatButton mSignOut;
  AppCompatTextView mGivenName;
  AppCompatTextView mFamilyName;
  AppCompatTextView mFullName;
  ImageView mProfileView;

  // login hint;
  String mLoginHint;

  // broadcast receiver for app restrictions changed broadcast
  private BroadcastReceiver mRestrictionsReceiver;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    mMainApplication = (MainApplication) getApplication();
    mAuthorize = (AppCompatButton) findViewById(R.id.authorize);
    mMakeApiCall = (AppCompatButton) findViewById(R.id.makeApiCall);
    mSignOut = (AppCompatButton) findViewById(R.id.signOut);
    mGivenName = (AppCompatTextView) findViewById(R.id.givenName);
    mFamilyName = (AppCompatTextView) findViewById(R.id.familyName);
    mFullName = (AppCompatTextView) findViewById(R.id.fullName);
    mProfileView = (ImageView) findViewById(R.id.profileImage);

    enablePostAuthorizationFlows();

    // wire click listeners
    mAuthorize.setOnClickListener(new AuthorizeListener(this));

    // Retrieve app restrictions and take appropriate action
    getAppRestrictions();
  }

  public String getLoginHint(){
        return mLoginHint;
  }

  private void enablePostAuthorizationFlows() {
    mAuthState = restoreAuthState();
    if (mAuthState != null && mAuthState.isAuthorized()) {
      if (mMakeApiCall.getVisibility() == View.GONE) {
        mMakeApiCall.setVisibility(View.VISIBLE);
        mMakeApiCall.setOnClickListener(new MakeApiCallListener(this, mAuthState, new AuthorizationService(this)));
      }
      if (mSignOut.getVisibility() == View.GONE) {
        mSignOut.setVisibility(View.VISIBLE);
        mSignOut.setOnClickListener(new SignOutListener(this));
      }
    } else {
      mMakeApiCall.setVisibility(View.GONE);
      mSignOut.setVisibility(View.GONE);
    }
  }

  /**
   * Exchanges the code, for the {@link TokenResponse}.
   *
   * @param intent represents the {@link Intent} from the Custom Tabs or the System Browser.
   */
  private void handleAuthorizationResponse(@NonNull Intent intent) {

      AuthorizationResponse response = AuthorizationResponse.fromIntent(intent);
      AuthorizationException error = AuthorizationException.fromIntent(intent);
      final AuthState authState = new AuthState(response, error);

      if (response != null) {
          Log.i(LOG_TAG, String.format("Handled Authorization Response %s ", authState.toJsonString()));
          AuthorizationService service = new AuthorizationService(this);
          service.performTokenRequest(response.createTokenExchangeRequest(), new AuthorizationService.TokenResponseCallback() {
              @Override
              public void onTokenRequestCompleted(@Nullable TokenResponse tokenResponse, @Nullable AuthorizationException exception) {
                  if (exception != null) {
                      Log.w(LOG_TAG, "Token Exchange failed", exception);
                  } else {
                      if (tokenResponse != null) {
                          authState.update(tokenResponse, exception);
                          persistAuthState(authState);
                          Log.i(LOG_TAG, String.format("Token Response [ Access Token: %s, ID Token: %s ]", tokenResponse.accessToken, tokenResponse.idToken));
                      }
                  }
              }
          });
      }
  }

  private void persistAuthState(@NonNull AuthState authState) {
    getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
        .putString(AUTH_STATE, authState.toJsonString())
        .commit();
    enablePostAuthorizationFlows();
  }

  private void clearAuthState() {
    getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(AUTH_STATE)
        .apply();
  }

  @Nullable
  private AuthState restoreAuthState() {
    String jsonString = getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(AUTH_STATE, null);
    if (!TextUtils.isEmpty(jsonString)) {
      try {
        return AuthState.fromJson(jsonString);
      } catch (JSONException jsonException) {
        // should never happen
      }
    }
    return null;
  }

  /**
   * Kicks off the authorization flow.
   */
  public static class AuthorizeListener implements Button.OnClickListener {

    private final MainActivity mMainActivity;

    public AuthorizeListener(@NonNull MainActivity mainActivity) {
          mMainActivity = mainActivity;
    }

    @Override
    public void onClick(View view) {

          AuthorizationServiceConfiguration serviceConfiguration = new AuthorizationServiceConfiguration(
                  Uri.parse("https://accounts.google.com/o/oauth2/v2/auth") /* auth endpoint */,
                  Uri.parse("https://www.googleapis.com/oauth2/v4/token") /* token endpoint */
          );

          String clientId = "511828570984-fuprh0cm7665emlne3rnf9pk34kkn86s.apps.googleusercontent.com";
          Uri redirectUri = Uri.parse("com.google.codelabs.appauth:/oauth2callback");

          AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(
                  serviceConfiguration,
                  clientId,
                  AuthorizationRequest.RESPONSE_TYPE_CODE,
                  redirectUri
          );
          builder.setScopes("https://www.googleapis.com/auth/taskqueue https://www.googleapis.com/auth/taskqueue.consumer");

          if(mMainActivity.getLoginHint() != null){
                Map loginHintMap = new HashMap<String, String>();
                loginHintMap.put(LOGIN_HINT,mMainActivity.getLoginHint());
                builder.setAdditionalParameters(loginHintMap);

                Log.i(LOG_TAG, String.format("login_hint: %s", mMainActivity.getLoginHint()));

                Log.i(LOG_TAG, String.format("login_hint: %s", mMainActivity.getLoginHint()));
          }

          AuthorizationRequest request = builder.build();

          AuthorizationService authorizationService = new AuthorizationService(view.getContext());

          String action = "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE";
          Intent postAuthorizationIntent = new Intent(action);
          PendingIntent pendingIntent = PendingIntent.getActivity(view.getContext(), request.hashCode(), postAuthorizationIntent, 0);
          authorizationService.performAuthorizationRequest(request, pendingIntent);
        }
  }

   @Override
   protected void onNewIntent(Intent intent) {
       checkIntent(intent);
   }

   private void checkIntent(@Nullable Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            switch (action) {
                case "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE":
                    if (!intent.hasExtra(USED_INTENT)) {
                        handleAuthorizationResponse(intent);
                        intent.putExtra(USED_INTENT, true);
                    }
                    break;
                default:
                    // do nothing
            }
        }
   }

   @Override
   protected void onStart() {
        super.onStart();
        checkIntent(getIntent());

       // Register a receiver for app restrictions changed broadcast
       registerRestrictionsReceiver();
   }

    @Override
    protected void onResume(){
        super.onResume();

        // Retrieve app restrictions and take appropriate action
        getAppRestrictions();

        // Register a receiver for app restrictions changed broadcast
        registerRestrictionsReceiver();
    }

    @Override
    protected void onStop(){
        super.onStop();

        // Unregister receiver for app restrictions changed broadcast
        unregisterReceiver(mRestrictionsReceiver);
    }

   private void getAppRestrictions(){
        RestrictionsManager restrictionsManager =
                (RestrictionsManager) this
                        .getSystemService(Context.RESTRICTIONS_SERVICE);

        Bundle appRestrictions = restrictionsManager.getApplicationRestrictions();

        if(!appRestrictions.isEmpty()){
            if(appRestrictions.getBoolean(UserManager.
                    KEY_RESTRICTIONS_PENDING)!=true){
                mLoginHint = appRestrictions.getString(LOGIN_HINT);
            }
            else {
                Toast.makeText(this,R.string.restrictions_pending_block_user,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
   }

   private void registerRestrictionsReceiver(){
        IntentFilter restrictionsFilter =
                new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);

        mRestrictionsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                getAppRestrictions();
            }
        };

        registerReceiver(mRestrictionsReceiver, restrictionsFilter);
   }

  public static class SignOutListener implements Button.OnClickListener {

    private final MainActivity mMainActivity;

    public SignOutListener(@NonNull MainActivity mainActivity) {
      mMainActivity = mainActivity;
    }

    @Override
    public void onClick(View view) {
      mMainActivity.mAuthState = null;
      mMainActivity.clearAuthState();
      mMainActivity.enablePostAuthorizationFlows();
    }
  }

    public static class MakeApiCallListener implements Button.OnClickListener {

        private final MainActivity mMainActivity;
        private AuthState mAuthState;
        private AuthorizationService mAuthorizationService;

        public MakeApiCallListener(@NonNull MainActivity mainActivity, @NonNull AuthState authState, @NonNull AuthorizationService authorizationService) {
            mMainActivity = mainActivity;
            mAuthState = authState;
            mAuthorizationService = authorizationService;
        }

        @Override
        public void onClick(View view) {
            mAuthState.performActionWithFreshTokens(mAuthorizationService, new AuthState.AuthStateAction() {
                @Override
                public void execute(@Nullable String accessToken, @Nullable String idToken, @Nullable AuthorizationException exception) {
                    new AsyncTask<String, Void, JSONObject>() {

                        Boolean leased = false; // Flag for leasing

                        @Override
                        protected JSONObject doInBackground(String... tokens) {
                            // Prepare for leasing a task
                            String taskNumber;
                            JSONObject jsonObject;
                            OkHttpClient client = new OkHttpClient();
                            Request request = new Request.Builder()
                                    .url("https://www.googleapis.com/taskqueue/v1beta2/projects/testcloudstorage-1470232940384/taskqueues/pull-queue/tasks/lease?leaseSecs=" + leasePeriod + "&numTasks=1")
                                    .method("POST", RequestBody.create(null, new byte[0]))
                                    .addHeader("Authorization", String.format("Bearer %s", tokens[0]))
                                    .build();
                            try {
                                // Lease a task and get response in the jsonObject
                                Response response = client.newCall(request).execute();
                                String jsonBody = response.body().string();
                                Log.i(LOG_TAG, String.format("Taskqueue Response %s", jsonBody));
                                jsonObject = new JSONObject(jsonBody);

                                // If leasing was successful, get the id of the task, and delete the task using the id.
                                if ( jsonObject.getJSONArray("items") != null) {
                                    leased = true;
                                    taskNumber = (String)jsonObject.getJSONArray("items").getJSONObject(0).get("id");
                                    request = new Request.Builder()
                                            .url("https://www.googleapis.com/taskqueue/v1beta2/projects/s~testcloudstorage-1470232940384/taskqueues/pull-queue/tasks/" + taskNumber)
                                            .method("DELETE", RequestBody.create(null, new byte[0]))
                                            .addHeader("Authorization", String.format("Bearer %s", tokens[0]))
                                            .build();
                                    client.newCall(request).execute();
                                // If there was no task to lease,
                                } else {
                                    Log.i(LOG_TAG, String.format("There is no task to lease."));
                                }
                                // Pass the response to PostExecute
                                return jsonObject;
                            } catch (Exception exception) {
                                Log.w(LOG_TAG, exception);
                            }

                            return null;
                        }

                        @Override
                        protected void onPostExecute(JSONObject jsonBody) {
                            if (jsonBody != null) {

                                byte[] decoded = null;
                                String payload = null;
                                JSONArray jsonArray;
                                JSONObject payloadObject;

                                try {
                                    try {
                                        if (leased == true) {
                                            jsonArray = jsonBody.getJSONArray("items");
                                            payloadObject = jsonArray.getJSONObject(0);
                                            decoded = Base64.decode( (String) payloadObject.get("payloadBase64"), Base64.NO_WRAP);
                                            payload = new String(decoded, "UTF-8");
                                            leased = false;
                                        } else {
                                            payload = "No task to lease";
                                        }
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }

                                if (!TextUtils.isEmpty((payload))) {
                                    mMainActivity.mFamilyName.setText(payload);
                                }

                                String message;
                                if (jsonBody.has("error")) {
                                    message = String.format("%s [%s]", mMainActivity.getString(R.string.request_failed), jsonBody.optString("error_description", "No description"));
                                } else {
                                    message = mMainActivity.getString(R.string.request_complete);
                                }
                                Snackbar.make(mMainActivity.mProfileView, message, Snackbar.LENGTH_SHORT)
                                        .show();
                            } else {
                                mMainActivity.mFamilyName.setText("No task to lease");
                            }
                        }
                    }.execute(accessToken);
                }
            });
        }
    }
}
