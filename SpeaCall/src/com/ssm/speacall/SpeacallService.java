package com.ssm.speacall;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.android.internal.telephony.ITelephony;

public class SpeacallService extends Service  {

	private static final String TAG = "calllog";

	// PORT
	private static final int SERVER_PORT = 9500;

	int bufferSize = 10000;
	final int SAMPLE_RATE = 48000;

	// Message input/output situation division
	public static final int MSG_FROM_CLIENT = 1;
	private static final int MSG_TO_CLIENT = 2;

	public static final String MESSAGE_TYPE_INBOX = "1";
	public static final String MESSAGE_TYPE_SENT = "2";
	public static final String MESSAGE_TYPE_CONVERSATIONS = "3";
	public static final String MESSAGE_TYPE_REJECT_SAMSUNG = "4";
	public static final String MESSAGE_TYPE_REJECT = "5";
	public static final String MESSAGE_TYPE_MESSAGE_RECEIVED = "13";
	public static final String MESSAGE_TYPE_MESSAGE_SEND = "14";
	final static private String[] Call_Projection = { CallLog.Calls.TYPE,
		CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
		CallLog.Calls.DATE, CallLog.Calls.DURATION };

	String mPhoneNumber = "";
	boolean checkMessage = true;
	boolean checkCall = false;
	boolean checkIdle = false;
	boolean checkRing = false;
//	boolean checkCallFromPC = false;
	boolean checkloop = true;

	Contact contact = new Contact();
	AudioTrack at;
	ContactTask contactTask;
	SocketHandler handler;
	SocketServer server=null;;
	Socket mClient = null;
	Thread serverThread;
	CallListTask mCallListTask;
	CallListTaskAgain mCallListTaskAgain;
	ServerSocket mSocketServer = null;
	TelephonyManager mTelephonyManager;
	BufferedInputStream bis = null;
	String phoneNumber = "";

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub

		return null;
	}

	@Override
	public void onCreate() {
		// TODO Auto-generated method stub
		super.onCreate();
		Log.d("hak", "onCreate");
		
		IntentFilter filter = new IntentFilter();
		filter.addAction("android.intent.action.ACTION_POWER_DISCONNECTED");
		filter.addAction("android.intent.action.NEW_OUTGOING_CALL");
		getApplicationContext().registerReceiver(receiver, filter);

		mTelephonyManager = (TelephonyManager)this.getSystemService(Context.TELEPHONY_SERVICE);
		mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
		
		// message handler from PC
		SocketHandler handler = new SocketHandler();

		// start Socket Server 
		server = new SocketServer(handler);
		Thread serverThread = new Thread(server);
		serverThread.start();

		Toast toast = Toast.makeText(getApplicationContext(), "SpeaCall Start",
				Toast.LENGTH_SHORT);
		toast.show();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// TODO Auto-generated method stub
		return super.onStartCommand(intent, flags, startId);
	}

	public void onDestroy() {
		at.stop();
		//mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
		Toast toast = Toast.makeText(getApplicationContext(), "SpeaCall Service Stop",
				Toast.LENGTH_SHORT);
		toast.show();
		if(mClient != null){
			try {
				//Log.i("hak","Service Stop onDestroy in try");

				server.mServerWriter.close();
				mClient.close();
				bis.close();
				
			} catch (IOException e) { ; }
		}
		try {
			mSocketServer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Log.i("hak","Service Stop onDestroy");
		super.onDestroy();
	};
	
	// If USB Unplugged
	BroadcastReceiver receiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals("android.intent.action.ACTION_POWER_DISCONNECTED")) {
				checkloop = false;
				stopSelf();

			}

			if (intent.getAction().equals("android.intent.action.NEW_OUTGOING_CALL")) {
				phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
				if(mClient != null){
				SendCallingTask mSendCallTask = new SendCallingTask();
		        mSendCallTask.execute();
				}
		        
		    }
		}
	};

	// Phone State Listener Calling or Ringing or Idle
	PhoneStateListener phoneStateListener = new PhoneStateListener(){
		
		public void onCallStateChanged(int state, String incomingNumber){
			
			int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
					AudioFormat.CHANNEL_CONFIGURATION_STEREO,
					AudioFormat.ENCODING_PCM_16BIT);

			at = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
					AudioFormat.CHANNEL_CONFIGURATION_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
					AudioTrack.MODE_STREAM);
			String mCaller = "NoName";
			String caller = "";

			switch(state){
			case TelephonyManager.CALL_STATE_IDLE:
				// not calling or ringing
//				Log.i("speacall","STATE_IDLE");
				at.play();
				if(mClient != null){
					if(checkIdle){
						try {
							server.mServerWriter.write("SPEACALL"+5+"0000"+"\n");
							server.mServerWriter.flush();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						checkIdle = false;
					}
				}
				checkRing = true;
				
				break;
			case TelephonyManager.CALL_STATE_RINGING:
				// ringing a call bell
				phoneNumber = String.valueOf(incomingNumber);
				Log.i("speacall","STATE_ring" + incomingNumber);
				
	
				Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
				String[] projection = new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME};	

				Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
				if (cursor != null) {
					if (cursor.moveToFirst()){
						caller = cursor.getString(0);              
					}        
					cursor.close();
				}
				else
				{
					caller = "NoName";
				}
				mCaller = phoneNumber +"//"+caller;

				if(mClient != null){
					if(checkRing){
						try {
							
							if(String2Byte(mCaller)>9)
								server.mServerWriter.write("SPEACALL"+6+"00"+String2Byte(mCaller)+mCaller+"\n");
							else
								server.mServerWriter.write("SPEACALL"+6+"000"+String2Byte(mCaller)+mCaller+"\n");
							server.mServerWriter.flush();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						checkRing = false;
						checkCall = true;
					}
				}
				checkIdle=true;
				at.stop();
				break;
			case TelephonyManager.CALL_STATE_OFFHOOK:
				// calling
				Log.i("speacall","STATE_offhook");
				 uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
				 projection = new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME};	

				 cursor = getContentResolver().query(uri, projection, null, null, null);
				if (cursor != null) {
					if (cursor.moveToFirst()){
						caller = cursor.getString(0);              
					}        
					cursor.close();
				}
				else
				{
					caller = "NoName";
				}
				mCaller = phoneNumber +"//"+caller;

				if(checkCall){
					try {
						
						if(String2Byte(mCaller)>9)
							server.mServerWriter.write("SPEACALL"+7+"00"+String2Byte(mCaller)+mCaller+"\n");
						else
							server.mServerWriter.write("SPEACALL"+7+"000"+String2Byte(mCaller)+mCaller+"\n");
						server.mServerWriter.flush();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					checkCall=false;
				}
				checkIdle=true;
				at.stop();
				break;
				default :
					break;
			}
		};
	};

	// 안드로이드용 소켓 서버
	public class SocketServer implements Runnable {

		private InputStream is = null;
		private OutputStream os = null;
		public BufferedWriter mServerWriter = null;
		public BufferedReader mReaderFromClient = null;
		//public BufferedOutputStream bos = null;

		private byte[] buffer = new byte[bufferSize];
		private Handler mMainHandler;

		// message handler to pc
		Handler mMessageHandler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_TO_CLIENT:
					try {
						mServerWriter.write(msg.obj + "\n");
						mServerWriter.flush();
					} catch (IOException e) {
						e.printStackTrace();
					}
					break;

				default:
					break;
				}
			};
		};

		// socket server object maker
		public SocketServer(Handler handler) {
			this.mMainHandler = handler;
		}

		@Override
		public void run() {

			// Start socket communication
			try {
				mSocketServer = new ServerSocket(SERVER_PORT);
				mClient = mSocketServer.accept();
				is = mClient.getInputStream();
				os = mClient.getOutputStream();
				mServerWriter = new BufferedWriter(new OutputStreamWriter(os));
				bis = new BufferedInputStream(is);

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			try {
				TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
				Class<?> c = Class.forName(tm.getClass().getName());
				Method m = c.getDeclaredMethod("getITelephony");
				m.setAccessible(true);
				ITelephony telephonyService = (ITelephony) m.invoke(tm);
				while (checkloop) {
					waitHeader(bis);
					int type = getType(bis);
					int payloadLen = getPayloadLength(bis);
					switch (type) {
					case 0:
						try {
							// 소켓 종료
							mClient.close();
							mServerWriter.close();
							mSocketServer.close();
						} catch (Exception e) {
							e.printStackTrace();
						}
						stopSelf();
						//stopService(new Intent(getApplicationContext(), SpeacallService.class));
						break;
						
					case 1:
						// if audio data
						playSound(bis, payloadLen);
						break;
						
					case 2:
						// if contact
						getContactList();
						contactTask = new ContactTask();
						contactTask.execute();
						break;
						
					case 3:
						// if CallLog
						mCallListTask = new CallListTask();
						mCallListTask.execute();
						break;
					case 4:
						// call number
						//Log.d("hak", " start and payload len :  "+ payloadLen);
						getPhone( bis,  payloadLen);
						break;	
					case 5:
						// receive call
						//Log.d("hak","call receive");
						Intent buttonDown = new Intent(Intent.ACTION_MEDIA_BUTTON);		
						buttonDown.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
						getBaseContext().sendOrderedBroadcast(buttonDown, "android.permission.CALL_PRIVILEGED");

						Intent buttonUp = new Intent(Intent.ACTION_MEDIA_BUTTON);		
						buttonUp.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
						getBaseContext().sendOrderedBroadcast(buttonUp, "android.permission.CALL_PRIVILEGED");
						break;		
					case 6:
						// call finish
						//Log.d("hak","call end");
						telephonyService.endCall();
						break;
					case 7:
						mCallListTaskAgain = new CallListTaskAgain();
						mCallListTaskAgain.execute();
						break;
					case 8:
						buffer = new byte[bufferSize];
						//buffer.
						playSound(bis, payloadLen);
						break;

					default :
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		private void waitHeader(BufferedInputStream bis) {
			byte[] header = "SPEACALL".getBytes();
			int i = 0;
			int cnt=0;
			while (i < header.length) {

				try {
					if (bis.read() == header[i]){
						i++;
					}else
					{
						i = 0;
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if(cnt++>30)
					break;
			}
		}

		private int getType(BufferedInputStream bis) {
			int type = 0;
			try {
				type = bis.read();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return -1;
			}
			return type;
		}

		private int getPayloadLength(BufferedInputStream bis) {
			int length = 0;
			//byte[] buffer = new byte[4];
			try {
				length += bis.read() << 24;
				length += bis.read() << 16;
				length += bis.read() << 8;
				length += bis.read() << 0;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return -1;
			}
			return length;
		}

		private void playSound(BufferedInputStream bis, int payloadLen) {
			int readPayloadLen = 0;
			while(readPayloadLen < payloadLen) {
				try {
					int readLen = bis.read(buffer, 0
							, (buffer.length < payloadLen) ? buffer.length : payloadLen - readPayloadLen);
					readPayloadLen += readLen;
					at.write(buffer, 0, readLen);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		private void getPhone(BufferedInputStream bis, int payloadLen) {
			int readPayloadLen = 0;
			while(readPayloadLen < payloadLen) {
				try {
					int readLen = bis.read(buffer, 0, (buffer.length < payloadLen) ? buffer.length : payloadLen - readPayloadLen);
					readPayloadLen += readLen;
					mPhoneNumber = new String(buffer,0,readLen);
					//Log.d("hak", "this is  : "+mPhoneNumber +" & readLen : "+ readLen);

				} catch (IOException e) {
					// TODO Auto-generated catch block
					Log.d("hak","getphone is non working");
					e.printStackTrace();
				} 
			}
			Intent callIntent = new Intent(Intent.ACTION_CALL,Uri.parse("tel:"+mPhoneNumber));
			callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(callIntent);
		}
	}

	// PC에서 보내온 메시지를 TextView에 표시
	private class SocketHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case MSG_FROM_CLIENT:
				String message = (String) msg.obj;
				//Log.d("hak", "message from pc: " + message);
				break;

			default:
				break;
			}
		}
	}
	

	private class SendCallingTask extends AsyncTask<Void, Intent, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				String caller = "";
				String mCaller = "NoName";
		        
				
				Uri uriCall = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
				String[] projectionCall = new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME};
				
				Cursor cursorC = getContentResolver().query(uriCall, projectionCall, null, null, null);
				if (cursorC != null) {
					if (cursorC.moveToFirst()){
						caller = cursorC.getString(0);              
					}        
					cursorC.close();
				}
				else
				{
					caller = "NoName";
				}
				mCaller = phoneNumber +"//"+caller;

				try {
					
					if(String2Byte(mCaller)>9)
					{
						server.mServerWriter.write("SPEACALL"+7+"00"+String2Byte(mCaller)+mCaller+"\n");
					}	
					else{
						server.mServerWriter.write("SPEACALL"+7+"000"+String2Byte(mCaller)+mCaller+"\n");
					}
					server.mServerWriter.flush();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "contact task is not working");
			}
			return null;
		}
	}
	

	private class ContactTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				// get Contact list
				String mMessage = null;
				int cnt=0;
				ArrayList<String> arGeneral = new ArrayList<String>();
				ArrayList<Contact> arContactList = new ArrayList<Contact>();
				arContactList = getContactList();
				int listsize = arContactList.size();
				for (int i = 0; i < arContactList.size(); i++) {

					try{
						arGeneral.add(arContactList.get(i).phonenum);
						//Log.d("hak","저나버노"+arContactList.get(i).phonenum);
						if  ((i <= listsize-2) && (arContactList.get(i).phonenum.equals(arContactList.get(i + 1).phonenum) || arContactList.get(i).phonenum.equals(arContactList.get(i + 2).phonenum)) ) {
							//mMessage = null;
							//Log.d("hak","speacall cnt : "+String.valueOf(cnt++) + " i : "+i);
							continue;
						}
					}catch (Exception e) {
						e.printStackTrace();
						Log.d("hak", "de-duplicate is not working");
					}

					mMessage = arContactList.get(i).phonenum.toString() + "//"
							+ arContactList.get(i).getName().toString();
					if(mClient != null){
						server.mServerWriter.write("SPEACALL"+4+"00"+  String2Byte( mMessage) +mMessage+"\n");
						server.mServerWriter.flush();
						Thread.sleep(1);
						
					}
//					Log.d("hak","SPEACALL"+4+"00"+  String2Byte( mMessage) +mMessage);
					mMessage = null;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "contact task is not working");
			}
			return null;
		}
	}

	private int String2Byte(String message) throws UnsupportedEncodingException
	{
		byte nByte[] = message.getBytes("UTF-8");

		return nByte.length;
	}

	// Contact
	private ArrayList<Contact> getContactList() {
		Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
		String[] projection = new String[] {
				ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
				ContactsContract.CommonDataKinds.Phone.NUMBER,
				ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME };

		String[] selectionArgs = null;
		String sortOrder = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
				+ " COLLATE LOCALIZED ASC";

		Cursor contactCursor = getContentResolver().query(uri, projection,
				null, selectionArgs, sortOrder);
		ArrayList<Contact> contactlist = new ArrayList<Contact>();
		if (contactCursor.moveToFirst()) {
			do {
				String phonenumber = contactCursor.getString(1).replaceAll("-",
						"");
				Contact acontact = new Contact();
				acontact.setPhotoid(contactCursor.getLong(0));
				acontact.setPhonenum(phonenumber);
				acontact.setName(contactCursor.getString(2));
				contactlist.add(acontact);
			} while (contactCursor.moveToNext());
		}
		return contactlist;
	}

	// Get Call Log
	private Cursor getCallHistoryCursor(Context context) {
		Cursor cursor = context.getContentResolver().query(
				CallLog.Calls.CONTENT_URI, Call_Projection, null, null,
				CallLog.Calls.DEFAULT_SORT_ORDER);
		return cursor;
	}

	private void callLog() {
		int callcount = 0;
		String callname = "";
		String calltype = "";
		Cursor curCallLog = getCallHistoryCursor(this);
		if (curCallLog.moveToFirst() && curCallLog.getCount() > 0) {
			while (curCallLog.isAfterLast() == false && callcount<100) {
				StringBuffer sb = new StringBuffer();
				String callLog=null;

				if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_INBOX)) {
					calltype = "수신";
				} else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_SENT)) {
					calltype = "발신";
				} else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_CONVERSATIONS)) {
					calltype = "부재중";
				}
				else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_REJECT)) {
					calltype = "수신거부";
				}
				else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_REJECT_SAMSUNG)) {
					calltype = "수신거부";
				}
				else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_MESSAGE_SEND)) {
					calltype = "문자";
				}
				else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
							MESSAGE_TYPE_MESSAGE_RECEIVED)) {
					calltype = "문자";
				}
				else{
					calltype = "문자";
				}

				if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.CACHED_NAME)) == null) {
					callname = "NoName";
				} else {
					callname = curCallLog.getString(curCallLog
							.getColumnIndex(CallLog.Calls.CACHED_NAME));
				}
				sb.append(timeToString(curCallLog.getLong(curCallLog
						.getColumnIndex(CallLog.Calls.DATE))));
				sb.append("//").append(calltype);

				sb.append("//").append(
						curCallLog.getString(curCallLog
								.getColumnIndex(CallLog.Calls.NUMBER)));
				sb.append("//").append(callname);


				callLog = timeToString(curCallLog.getLong(curCallLog
						.getColumnIndex(CallLog.Calls.DATE)))+"//"+calltype+"//"+curCallLog.getString(curCallLog
								.getColumnIndex(CallLog.Calls.NUMBER))+"//"+callname;
				curCallLog.moveToNext();
				//Log.d("calllog", callLog);
				callcount++;

				if(mClient != null){
				try 
				{
					if(String2Byte(callLog)>99)
					{
						server.mServerWriter.write("SPEACALL"+3+"0"+ String2Byte(callLog) +callLog+"\n");
					}
					else
					{
						server.mServerWriter.write("SPEACALL"+3+"00"+ String2Byte(callLog) +callLog+"\n");
					}
					server.mServerWriter.flush();
				} catch (UnsupportedEncodingException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				}

				try {
					Thread.sleep(1);
				} catch (InterruptedException e) { }
				//					Log.d("calllog", "SPEACALL"+3+"00"+sb.length()+sb.toString());
			}
		}
	}
	private class CallListTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				callLog();
			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "call list task is not working");
			}
			return null;
		}

	}

	private void callLogAgain() {
		int callcount = 0;
		String callname = "";
		String calltype = "";
		Cursor curCallLog = getCallHistoryCursor(this);
		if (curCallLog.moveToFirst() && curCallLog.getCount() > 0) {
			while (curCallLog.isAfterLast() == false && callcount == 0) {
				StringBuffer sb = new StringBuffer();
				String callLog=null;

				if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_INBOX)) {
					calltype = "수신";
				} else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_SENT)) {
					calltype = "발신";
				} else if (curCallLog.getString(
						curCallLog.getColumnIndex(CallLog.Calls.TYPE)).equals(
								MESSAGE_TYPE_CONVERSATIONS)) {
					calltype = "부재중";
				}
				else{
					calltype = "수신거부";
				}

				if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.CACHED_NAME)) == null) {
					callname = "NoName";
				} else {
					callname = curCallLog.getString(curCallLog
							.getColumnIndex(CallLog.Calls.CACHED_NAME));
				}
				sb.append(timeToString(curCallLog.getLong(curCallLog
						.getColumnIndex(CallLog.Calls.DATE))));
				sb.append("//").append(calltype);

				sb.append("//").append(
						curCallLog.getString(curCallLog
								.getColumnIndex(CallLog.Calls.NUMBER)));
				sb.append("//").append(callname);


				callLog = timeToString(curCallLog.getLong(curCallLog
						.getColumnIndex(CallLog.Calls.DATE)))+"//"+calltype+"//"+curCallLog.getString(curCallLog
								.getColumnIndex(CallLog.Calls.NUMBER))+"//"+callname;
				curCallLog.moveToNext();
				//Log.d("calllog", callLog);
				callcount++;

				try 
				{
					if(String2Byte(callLog)>99)
					{
						server.mServerWriter.write("SPEACALL"+2+"0"+ String2Byte(callLog) +callLog+"\n");
					}
					else
					{
						server.mServerWriter.write("SPEACALL"+2+"00"+ String2Byte(callLog) +callLog+"\n");
					}
					server.mServerWriter.flush();
				} catch (UnsupportedEncodingException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}

				try {
					Thread.sleep(1);
				} catch (InterruptedException e) { }
				//					Log.d("calllog", "SPEACALL"+3+"00"+sb.length()+sb.toString());
			}
		}
	}
	private class CallListTaskAgain extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				Thread.sleep(500);
				callLogAgain();
			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "call list task is not working");
			}
			return null;
		}

	}
	private String timeToString(Long time) {
		SimpleDateFormat simpleFormat = new SimpleDateFormat(
				"yyyy-MM-dd//HH:mm:ss");
		String date = simpleFormat.format(new Date(time));
		return date;
	}

}
