package io.jiaozi;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Jiaozi {

    private void Jiaozi(){}
    private static Jiaozi instance;
    private static Activity activity;
    private static String endPoint = "10.15.20.246";
    private static String current_exp_id = null;


    /**
     * Init Tracker with current activity
     * @param activity
     */
    public static void init(Activity activity){
        Jiaozi.activity = activity;
    }

    /**
     * Set tracker profile_id.
     * Please contact Jiaozi provider for this id
     * @param profile_id
     */
    public static void config(String profile_id){
        String uuid = getUUID();
        Jiaozi.config(profile_id,uuid);
    }

    /**
     * Set tracker profile id.
     * And manually define device unique id
     * @param profile_id
     * @param uuid
     */
    public static void config(String profile_id,String uuid){
        saveConfig("profile_id",profile_id);
        saveConfig("uuid",uuid);
    }

    /**
     * If user already login.
     * Should pass an encrypted user_id
     * @param userId
     */
    public static void setUserId(String userId){
        Jiaozi.saveConfig("user_id",userId);
    }

    public static void removeUserId(){
        removeConfig("user_id");
    }

    /*
     * Manual Event Track Method
     */

    public static void track(String category,String action){
        trackEvent(category,action,null,null);
    }
    public static void track(String category,String action,String label){
        trackEvent(category,action,label,null);
    }
    public static void track(String category,String action,String label,String value){
        Map<String,String> extra_value = new HashMap<String, String>();
        extra_value.put("value",value);
        trackEvent(category,action,label,extra_value);
    }
    public static void track(String category,String action,String label,double value){
        track(category,action,label,value,null);
    }

    /**
     * Track User behavior with this parameters
     * @param category
     * @param action
     * @param label
     * @param value
     * @param extra if you have much more event info to send.use this parameter
     */
    public static void track(String category,String action,String label,double value,Map<String,String> extra){
        extra.put("value_number",Double.toString(value));
        trackEvent(category,action,label,extra);
    }

    /**
     * Get Variation when experiment is running
     * @param experiment_id
     * @param callback
     */
    public static void getVariation(final String experiment_id, final Callback callback){
        Jiaozi.current_exp_id = experiment_id;
        request("experiments/" + getConfig("profile_id")+".json", null, new Callback() {
            @Override
            public void onResult(boolean success, Object data) {
                //request error
                if(success==false){
                    callback.onResult(false,-1);
                    return;
                }

                try{
                    //search for match experiment
                    JSONObject experiment = null;
                    JSONArray experiments = new JSONArray((String)data);
                    for(int i=0;i<experiments.length();i++){
                        experiment = experiments.getJSONObject(i);
                        String exp_id = experiment.getString("experiment_id");
                        if(exp_id.equals(experiment_id)){
                            JSONObject filter = experiment.getJSONObject("filter");
                            if("Android".equalsIgnoreCase(filter.getString("os"))
                                    && getAppVersion().equalsIgnoreCase(filter.getString("client_version"))){
                                break;
                            }

                        }
                        experiment = null;
                    }

                    String variation_key = getVariationKey(experiment_id);
                    //do not match current experiment
                    if(experiment==null){
                        removeConfig(variation_key);
                        callback.onResult(true,null);
                        return;
                    }

                    //check if already assign variation
                    String savedVariation = getConfig(variation_key);
                    if(savedVariation!=null && !savedVariation.isEmpty()){
                        callback.onResult(true,savedVariation);
                        return;
                    }

                    //random if in experiment traffic
                    double traffic_in_exp = experiment.getDouble("traffic_in_exp");
                    double guess = Math.random();
                    if(guess>traffic_in_exp){
                        saveConfig(variation_key,"-1");
                        callback.onResult(true,-1);
                        return;
                    }

                    //if in experiment,assign variation
                    JSONArray variations = experiment.getJSONArray("variations");
                    guess = Math.random();
                    double weight = 0;
                    for(int i=0;i<variations.length();i++){
                        JSONObject variation = variations.getJSONObject(i);
                        weight += variation.getDouble("weight");
                        if(guess<weight){
                            saveConfig(variation_key,variation.getString("index"));
                            callback.onResult(true,variation.getInt("index"));
                            break;
                        }
                    }

                }catch (Exception ex){
                    Log.e(ex.getMessage(),ex.toString());
                    callback.onResult(false,-1);
                }

            }
        });
    }

    protected static void trackEvent(String category,String action,String label,Map<String,String> value){
        Map<String,String> params = new HashMap<>();
        params.put("category",category);
        params.put("action",action);
        if(label !=null && !label.isEmpty()){
            params.put("label",label);
        }
        if(value!=null){
            if(value.containsKey("value")){
                params.put("value",value.get("value"));
                value.remove("value");
            }
            if(value.containsKey("value_number")){
                params.put("value_number",value.get("value_number"));
                value.remove("value_number");
            }
            JSONObject json = new JSONObject(value);
            params.put("value",json.toString());
        }

        params.put("type","event");
        request("collect_img.gif",params,null);
    }

    /**
     * Get previous Device UUID or generate new if empty
     * @return
     */
    protected static String getUUID(){
        String uuid = getConfig("uuid");
        if(uuid==null){
            uuid = UUID.randomUUID().toString();
        }
        return uuid;
    }

    protected static void saveConfig(String key,String value){
        SharedPreferences sp = activity.getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(Jiaozi.class.getName()+"."+key,value);
        editor.commit();
    }

    protected static String getConfig(String key){
        SharedPreferences sp = activity.getPreferences(Context.MODE_PRIVATE);
        return sp.getString(Jiaozi.class.getName()+"."+key,null);
    }

    protected static void removeConfig(String key){
        SharedPreferences sp = activity.getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.remove(Jiaozi.class.getName()+"."+key);
        editor.commit();
    }


    protected static OkHttpClient client = null;

    /**
     * Common request method
     * @param path url path after domain
     * @param params url parameters after question mark
     * @param callback null if no need callback
     */
    protected static void request(String path, Map<String,String> params, final Callback callback){
        if(Jiaozi.client==null){
            Jiaozi.client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10,TimeUnit.SECONDS)
                    .readTimeout(60,TimeUnit.SECONDS)
                    .build();
        }
        HttpUrl.Builder url = new HttpUrl.Builder()
                .scheme("http")
                .host(Jiaozi.endPoint)
                .port(8080)
                .addPathSegments(path);
        if(params != null){
            for(Map.Entry<String,String> entry : params.entrySet()){
                url.addQueryParameter(entry.getKey(),entry.getValue());
            }
        }

        url.addQueryParameter("pid",getConfig("profile_id"));
        url.addQueryParameter("_jiaozi_uid",getConfig("uuid"));
        String user_id = getConfig("user_id");
        if(user_id!=null && !user_id.isEmpty()){
            url.addQueryParameter("user_id",user_id);
        }
        String variation_key = getVariationKey(Jiaozi.current_exp_id);
        String variation = getConfig(variation_key);
        if(variation!=null && !variation.isEmpty()){
            url.addQueryParameter("exp_var",current_exp_id+":"+variation);
        }



        Request request = new Request
                .Builder()
                .url(url.build())
                .header("user-agent",getUserAgent())
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if(callback!=null){
                    Log.e(Jiaozi.class.getName(),"request error:"+e.getMessage());
                    callback.onResult(false,null);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if(callback!=null){
                    String json = null;
                    try{
                        json = response.body().string();
                    }catch (Exception ex){
                        Log.e(ex.getMessage(),ex.toString());
                        callback.onResult(false,null);
                    }
                    callback.onResult(true,json);
                }

            }
        });
    }

    protected static String userAgent = null;
    protected static String getUserAgent(){
        if(userAgent==null){
            try{
                String packageName = activity.getPackageName();
                String appName = packageName.substring(packageName.lastIndexOf('.')+1);
                userAgent = String.format("%s/%s Android/%s (%s)", appName,getAppVersion(), Build.VERSION.RELEASE,Build.MODEL);
            }catch (Exception ex){
                Log.e(ex.getMessage(),ex.toString());
                userAgent = "unknown agent";
            }
        }
        return userAgent;
    }

    protected static String getAppVersion(){
        try{
            return activity.getPackageManager().
                    getPackageInfo(activity.getPackageName(),0).versionName;
        }catch (Exception ex){
            Log.e(ex.getMessage(),ex.toString());
            return "-1.-1.-1";
        }

    }

    protected static String getVariationKey(String experiment_id){
        return "exp."+experiment_id+".var";
    }

}
