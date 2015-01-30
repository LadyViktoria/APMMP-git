package edu.virginia.dtc.MCMservice;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import edu.virginia.dtc.SysMan.Biometrics;
import edu.virginia.dtc.SysMan.CGM;
import edu.virginia.dtc.SysMan.Constraints;
import edu.virginia.dtc.SysMan.Debug;
import edu.virginia.dtc.SysMan.Event;
import edu.virginia.dtc.SysMan.FSM;
import edu.virginia.dtc.SysMan.Meal;
import edu.virginia.dtc.SysMan.Params;
import edu.virginia.dtc.SysMan.Pump;
import edu.virginia.dtc.SysMan.Safety;
import edu.virginia.dtc.SysMan.State;
import edu.virginia.dtc.Tvector.Tvector;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.Toast;

public class MCMservice extends Service
{
	public static final String TAG = "MCMservice";
    public static final String IO_TEST_TAG = "MCMserviceIO";
    
	public int cycle_duration_seconds = 300;
	public int cycle_duration_mins = cycle_duration_seconds/60;
	
	private double latestCR = 0, latestCF = 0;
	
	private int DIAS_STATE, PUMP_STATE, PUMP_SERV_STATE, HYPO_LIGHT;
	private int TBR, SYNC;
	private double IOB;
	
    private Messenger mMessengerToService = null, mMessengerToActivity = null;
    private final Messenger mMessengerFromService = new Messenger(new IncomingHandler());
    
    //Content Observer
  	private SystemObserver sysObserver;
  	private PumpObserver pumpObserver;
  	private StateObserver stateObserver;
  	
  	private boolean systemBusy = false;
	
	private BroadcastReceiver mealActivityReceiver = new BroadcastReceiver()
	{
		final String FUNC_TAG = "mealActivityReceiver";
		
		@Override
		public void onReceive(Context context, Intent intent) 
		{
			Debug.i(TAG, FUNC_TAG, "Receiver called to start Meal Activity...");
			
			Intent ui = new Intent();
			ui.setClassName("edu.virginia.dtc.MCMservice", "edu.virginia.dtc.MCMservice.MealActivity");
			ui.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(ui);
		}
	};
	
	public void onCreate()
	{
		super.onCreate();
		
        log_action(TAG, "onCreate");
		
        // Set up a Notification for this Service
        String ns = Context.NOTIFICATION_SERVICE;
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);
        int icon = R.drawable.ic_launcher;
        CharSequence tickerText = "";
        long when = System.currentTimeMillis();
        Notification notification = new Notification(icon, tickerText, when);
        Context context = getApplicationContext();
        CharSequence contentTitle = "MCM";
        CharSequence contentText = "Meal Control Module";
        Intent notificationIntent = new Intent(this, MCMservice.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);
        final int MCM_ID = 100;
        
        startForeground(MCM_ID, notification);
        
        sysObserver = new SystemObserver(new Handler());
		getContentResolver().registerContentObserver(Biometrics.SYSTEM_URI, true, sysObserver);

		pumpObserver = new PumpObserver(new Handler());
        getContentResolver().registerContentObserver(Biometrics.PUMP_DETAILS_URI, true, pumpObserver);
        
        stateObserver = new StateObserver(new Handler());
        getContentResolver().registerContentObserver(Biometrics.STATE_URI, true, stateObserver);
        
        this.registerReceiver(mealActivityReceiver, new IntentFilter("DiAs.MealActivity"));
        
        readStartupValues();
	}
	
	@Override
	public IBinder onBind(Intent intent) 
	{
		return mMessengerFromService.getBinder();
	}
	
	@Override
	public void onDestroy()
	{
		super.onDestroy();
		
		if(mealActivityReceiver != null)
			this.unregisterReceiver(mealActivityReceiver);
		
		 if(stateObserver != null)
	        	getContentResolver().unregisterContentObserver(stateObserver);
	        
		if(sysObserver != null)
			getContentResolver().unregisterContentObserver(sysObserver);
		
		if(pumpObserver != null)
			getContentResolver().unregisterContentObserver(pumpObserver);
	}
	
	/************************************************************************************
	* Input and Calculations for Insulin
	************************************************************************************/
	
	private void analyzeInput(double bg, double carbs, double corr)
	{
		final String FUNC_TAG = "analyzeInput";
		
		//Get profile data
		subject_parameters();
		
		//Convert BG to mg/dL
		int blood_glucose_display_units = Params.getInt(getContentResolver(), "blood_glucose_display_units", CGM.BG_UNITS_MG_PER_DL);
		if (blood_glucose_display_units == CGM.BG_UNITS_MMOL_PER_L) 
			bg = bg * CGM.MGDL_PER_MMOLL;
		
		openLoopCalculation(bg, carbs, corr);
		
		updateActivity();
	}
	
	private void openLoopCalculation(double bg, double carbs, double corr)
	{
		final String FUNC_TAG = "openLoopCalculation";
		final double TARGET_BG = 110;
		
		if(bg > 39.0 && bg < 401.0) {
			double limit = (Constraints.MAX_CORR * latestCF) + TARGET_BG;
			Debug.i(TAG, FUNC_TAG, "CF: "+latestCF+" Limit: "+limit);
			
			MealActivity.bgValid = true;
			MealActivity.bgInsulin = (bg - TARGET_BG)*(1/latestCF);
		}
		else {
			MealActivity.bgValid = false;
		}
		
		if(carbs > 0) {
			double limit = Constraints.MAX_MEAL * latestCR;
			Debug.i(TAG, FUNC_TAG, "CR: "+latestCR+" Limit: "+limit);
			
			if(carbs <= limit) {
				MealActivity.carbsValid = true;
				MealActivity.carbsInsulin = carbs/latestCR;
			}
			else
				MealActivity.carbsValid = false;
		}
		else {
			MealActivity.carbsValid = false;
		}
		
		if(corr >= -20.0 && corr <= Constraints.MAX_CORR) {
			MealActivity.corrValid = true;
		}
		else {
			MealActivity.corrValid = false;
		}
		
		double total = 0.0;
		
		if(MealActivity.carbsValid)
			total += MealActivity.carbsInsulin;
		if(MealActivity.bgValid)
			total += MealActivity.bgInsulin;
		if(MealActivity.corrValid)
			total += MealActivity.corrInsulin;
		if(MealActivity.iobChecked)
			total -= MealActivity.iobInsulin;
		
		if(total > 0.0) {
			MealActivity.totalValid = true;
			MealActivity.totalInsulin = total;
		}
		else {
			MealActivity.totalValid = false;
		}
		
		if(systemBusy || !MealActivity.totalValid) {
			MealActivity.injectEnabled = false;
		}
		else {
			MealActivity.injectEnabled = true;
		}
	}
	
	/************************************************************************************
	* Auxillary Functions
	************************************************************************************/
	
	public void readStartupValues()
	{
		stateObserver.onChange(false, null);
		sysObserver.onChange(false, null);
		pumpObserver.onChange(false, null);
		
		checkSystem();
	}
	
	private void subject_parameters() 
	{
		final String FUNC_TAG = "subject_parameters";
		
		Tvector CR = new Tvector(24);
		Tvector CF = new Tvector(24);
		
		// Load up CR Tvector
	  	Cursor c=getContentResolver().query(Biometrics.CR_PROFILE_URI, null ,null, null, null);
 	  	c.moveToFirst();
 	  	if (c.getCount() != 0) {
 	 	  	CR.put(c.getLong(c.getColumnIndex("time")), c.getDouble(c.getColumnIndex("value")));
 	 	  	while (c.moveToNext()) {
 	 	 	  	CR.put(c.getLong(c.getColumnIndex("time")), c.getDouble(c.getColumnIndex("value")));
 	 	  	}
 	  	}
 	  	else {
 	  		Debug.e(TAG, FUNC_TAG, "CR_PROFILE_URI > c.getCount() == 0");
 	  	}
 	  	c.close();
 	  	
		// Load up CF Tvector
	  	c=getContentResolver().query(Biometrics.CF_PROFILE_URI, null ,null, null, null);
 	  	c.moveToFirst();
 	  	if (c.getCount() != 0) {
 	 	  	CF.put(c.getLong(c.getColumnIndex("time")), c.getDouble(c.getColumnIndex("value")));
 	 	  	while (c.moveToNext()) {
 	 	 	  	CF.put(c.getLong(c.getColumnIndex("time")), c.getDouble(c.getColumnIndex("value")));
 	 	  	}
 	  	}
 	  	else {
 	  		Debug.e(TAG, FUNC_TAG, "CF_PROFILE_URI > c.getCount() == 0");
 	  	}
 	  	c.close();
 	  	
		// Get the offset in minutes into the current day in the current time zone (based on cell phone time zone setting)
		long timeSeconds = (System.currentTimeMillis()/1000);
		TimeZone tz = TimeZone.getDefault();
		int UTC_offset_secs = tz.getOffset(timeSeconds*1000)/1000;
		int timeTodayMins = (int)((timeSeconds+UTC_offset_secs)/60)%1440;
		Debug.i(TAG, FUNC_TAG, "UTC_offset_secs="+UTC_offset_secs+", timeSeconds="+timeSeconds+", timeSeconds/60="+timeSeconds/60+", timeTodayMins="+timeTodayMins);
		
		// Get currently active CR value
		List<Integer> indices = new ArrayList<Integer>();
		indices = CR.find(">", -1, "<=", timeTodayMins);			// Find the list of indices <= time in minutes since today at 00:00
		if (indices == null) {
			indices = CR.find(">", -1, "<", -1);					// Use final value from the previous day's profile
		}
		else if (indices.size() == 0) {
			indices = CR.find(">", -1, "<", -1);					// Use final value from the previous day's profile
		}
		if (indices == null) {
			Debug.e(TAG, FUNC_TAG, "Missing CR daily profile");
		}
		else {
			latestCR = CR.get_value(indices.get(indices.size()-1));		// Return the last CR in this range						
		}
		
		// Get currently active CF value
		indices = new ArrayList<Integer>();
		indices = CF.find(">", -1, "<=", timeTodayMins);			// Find the list of indices <= time in minutes since today at 00:00
		if (indices == null) {
			indices = CF.find(">", -1, "<", -1);					// Use final value from the previous day's profile
		}
		else if (indices.size() == 0) {
			indices = CF.find(">", -1, "<", -1);					// Use final value from the previous day's profile
		}
		if (indices == null) {
			Debug.e(TAG, FUNC_TAG, "Missing CF daily profile");
		}
		else {
			latestCF = CF.get_value(indices.get(indices.size()-1));		// Return the last CF in this range						
		}
		Debug.i(TAG, FUNC_TAG, "latestCR="+latestCR+", latestCF="+latestCF);
	}
	
	private void checkSystem()
	{
		final String FUNC_TAG = "checkInjectButton";
	
		if(Pump.isBusy(PUMP_SERV_STATE) || TBR != FSM.IDLE || SYNC != FSM.IDLE)
		{
			Debug.i(TAG, FUNC_TAG, "Service state is busy!");
			systemBusy = true;
		}
		else if(!(PUMP_STATE == Pump.CONNECTED || PUMP_STATE == Pump.CONNECTED_LOW_RESV))
		{
			Debug.i(TAG, FUNC_TAG, "Pump is not connected!");
			systemBusy = true;
		}
		else if((HYPO_LIGHT == Safety.RED_LIGHT) && (DIAS_STATE != State.DIAS_STATE_OPEN_LOOP))
		{
			Debug.i(TAG, FUNC_TAG, "Red light...");
			systemBusy = true;
		}
		else
		{
			Debug.i(TAG, FUNC_TAG, "All is well...");
			systemBusy = false;
		}
		
		updateActivity();
	}
	
	private void updateActivity()
	{
		final String FUNC_TAG = "updateActivity";
		
		if(mMessengerToActivity != null)
		{
			try {
	    		Message msg = Message.obtain(null, Meal.MCM_CALCULATED, 0, 0);
	    		mMessengerToActivity.send(msg);
	        }
	        catch (RemoteException e) {
	    		e.printStackTrace();
	        }
		}
		else
			Debug.e(TAG, FUNC_TAG, "Messenger to activity is null!");
	}
	
	private void log_action(String service, String action) 
	{
		Intent i = new Intent("edu.virginia.dtc.intent.action.LOG_ACTION");
        i.putExtra("Service", service);
        i.putExtra("Status", action);
        i.putExtra("time", (long)(System.currentTimeMillis()/1000));
        sendBroadcast(i);
	}
	
	/************************************************************************************
	* Message Handler
	************************************************************************************/
	
	class IncomingHandler extends Handler 
    {
		final String FUNC_TAG = "messengerFromDiAsService";

    	Bundle responseBundle;
    	
    	@Override
        public void handleMessage(Message msg) {
            switch (msg.what) 
            {
	            case Meal.REGISTER:
	            	Debug.i(TAG, FUNC_TAG, "REGISTER");
	            	
					if (Params.getBoolean(getContentResolver(), "enableIO", false)) 
					{
                		Bundle b = new Bundle();
                		b.putString(	"description", "MealActivity >> (MCMservice), IO_TEST"+", "+FUNC_TAG+" REGISTER");
                		Event.addEvent(getApplicationContext(), Event.EVENT_SYSTEM_IO_TEST, Event.makeJsonString(b), Event.SET_LOG);
					}
					
					mMessengerToService = msg.replyTo;
	            	break;
	            case Meal.SSM_CALC_DONE:
	            	Debug.i(TAG, FUNC_TAG, "SSM_CALC_DONE");
	            	MealActivity.inProgress = false;
	            	readStartupValues();
	            	break;
	            case Meal.INJECT:
	            	break;
	            case Meal.UI_CLOSED:
	            	Debug.i(TAG, FUNC_TAG, "UI_CLOSED");
	    			try {
	    				mMessengerToService.send(Message.obtain(null, Meal.UI_CLOSED));
	    			} catch (RemoteException e) {
	    				e.printStackTrace();
	    			}
	            	break;
	            case Meal.UI_CHANGE:
	            	Debug.i(TAG, FUNC_TAG, "UI_CHANGE");
	            	
	            	Debug.i(TAG, FUNC_TAG, "BG: "+MealActivity.bg+" Carbs: "+MealActivity.carbs+" Corr: "+MealActivity.corrInsulin);
	            	analyzeInput(MealActivity.bg, MealActivity.carbs, MealActivity.corrInsulin);
	            	break;
	            case Meal.UI_REGISTER:
	            	Debug.i(TAG, FUNC_TAG, "UI_REGISTER");
	            	
	            	mMessengerToActivity = msg.replyTo;
	            	
	            	try {
	    				mMessengerToService.send(Message.obtain(null, Meal.UI_STARTED));
	    			} catch (RemoteException e) {
	    				e.printStackTrace();
	    			}
	            	break;
            }
        }
    }
	
	/************************************************************************************
	* Observers
	************************************************************************************/
	
	class StateObserver extends ContentObserver
	{
		private int count;
    	
    	public StateObserver(Handler handler) 
    	{
    		super(handler);
    		
    		final String FUNC_TAG = "State Observer";
    		Debug.i(TAG, FUNC_TAG, "Constructor");
    		
    		count = 0;
    	}

       @Override
       public void onChange(boolean selfChange) 
       {
    	   this.onChange(selfChange, null);
       }		

       @Override
       public void onChange(boolean selfChange, Uri uri) 
       {
    	   final String FUNC_TAG = "onChange";
    	   
    	   count++;
    	   
    	   Cursor c = getContentResolver().query(Biometrics.STATE_URI, new String[]{"sync_state", "tbr_state"}, null, null, null);
    	   if(c != null)
    	   {
    		   if(c.moveToLast())
    		   {
    			   SYNC = c.getInt(c.getColumnIndex("sync_state"));
    			   TBR = c.getInt(c.getColumnIndex("tbr_state"));
    		   }
    	   }
    	   c.close();
    	   
    	   checkSystem();
       }
	}
	
	class PumpObserver extends ContentObserver 
    {	
    	private int count;
    	
    	public PumpObserver(Handler handler) 
    	{
    		super(handler);
    		
    		final String FUNC_TAG = "Pump Observer";
    		Debug.i(TAG, FUNC_TAG, "Constructor");
    		
    		count = 0;
    	}

       @Override
       public void onChange(boolean selfChange) 
       {
    	   this.onChange(selfChange, null);
       }		

       @Override
       public void onChange(boolean selfChange, Uri uri) 
       {
    	   final String FUNC_TAG = "onChange";
    	   
    	   count++;
    	   
    	   Cursor c = getContentResolver().query(Biometrics.PUMP_DETAILS_URI, new String[]{"state", "service_state"}, null, null, null);
    	   if(c != null)
    	   {
    		   if(c.moveToLast())
    		   {
    			   PUMP_SERV_STATE = c.getInt(c.getColumnIndex("service_state"));
    		   }
    	   }
    	   c.close();
    	   
    	   checkSystem();
       }		
    }
	
	class SystemObserver extends ContentObserver 
    {	
    	private int count;
    	
    	public SystemObserver(Handler handler) 
    	{
    		super(handler);
    		
    		final String FUNC_TAG = "System Observer";
    		Debug.i(TAG, FUNC_TAG, "Constructor");
    		
    		count = 0;
    	}

       @Override
       public void onChange(boolean selfChange) 
       {
    	   this.onChange(selfChange, null);
       }		

       @Override
       public void onChange(boolean selfChange, Uri uri) 
       {
    	   final String FUNC_TAG = "onChange";
    	   
    	   count++;
    	   
    	   Cursor c = getContentResolver().query(Biometrics.SYSTEM_URI, null, null, null, null);
    	   if(c!=null)
    	   {
    		   if(c.moveToLast())
    		   {
    			   IOB = c.getDouble(c.getColumnIndex("iobValue"));
    			   if(IOB < 0.0)
    				   IOB = 0;
    			   
    			   MealActivity.iobInsulin = IOB;
    			   
    			   PUMP_STATE = c.getInt(c.getColumnIndex("pumpState"));
    			   DIAS_STATE = c.getInt(c.getColumnIndex("diasState"));
    			   HYPO_LIGHT = c.getInt(c.getColumnIndex("hypoLight"));
    			   
    			   if(!(PUMP_STATE == Pump.CONNECTED || PUMP_STATE == Pump.CONNECTED_LOW_RESV))
    			   {
    				   Debug.e(TAG, FUNC_TAG, "Pump is disconnected!  State: "+Pump.stateToString(PUMP_STATE));
    				   //Toast.makeText(getApplicationContext(), "Sorry, the pump is disconnected and a meal bolus cannot be processed!", Toast.LENGTH_LONG).show();
    				   //TODO: add some thing to close the UI when this occurs
    			   }
    		   }
    		   c.close();
    	   }
           
           checkSystem();
       }		
    }
}
