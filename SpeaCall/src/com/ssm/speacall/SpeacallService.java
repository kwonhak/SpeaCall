package com.ssm.speacall;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

public class SpeacallService extends Service  {

	private static final String TAG = "calllog";

	// PORT
	private static final int SERVER_PORT = 9500;

	int bufferSize = 4000;
	final int SAMPLE_RATE = 48000;

	// Message input/output situation division
	public static final int MSG_FROM_CLIENT = 1;
	private static final int MSG_TO_CLIENT = 2;

	public static final String MESSAGE_TYPE_INBOX = "1";
	public static final String MESSAGE_TYPE_SENT = "2";
	public static final String MESSAGE_TYPE_CONVERSATIONS = "3";
	public static final String MESSAGE_TYPE_NEW = "new";
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;

	boolean checkMessage = true;
	final static private String[] Call_Projection = { CallLog.Calls.TYPE,
		CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
		CallLog.Calls.DATE, CallLog.Calls.DURATION };

	Contact contact = new Contact();
	AudioTrack at;
	ContactTask contactTask;
	SocketHandler handler;
	SocketServer server;
	Socket mClient = null;
	Thread serverThread;
	AudioThread auThread;
	CallListTask mCallListTask;
	ServerSocket mSocketServer = null;
	TelephonyManager mTelephonyManager;
	public BufferedInputStream bis = null;

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		Log.d("hak","onBind part");

		return null;
	}

	@Override
	public void onCreate() {
		// TODO Auto-generated method stub
		super.onCreate();
		Log.d("hak", "onCreate");
		mTelephonyManager = (TelephonyManager)this.getSystemService(Context.TELEPHONY_SERVICE);
		mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
		// message handler from PC
		SocketHandler handler = new SocketHandler();

		// start Socket Server 
		server = new SocketServer(handler);
		Thread serverThread = new Thread(server);
		serverThread.start();

		Toast toast = Toast.makeText(getApplicationContext(), "App Start",
				Toast.LENGTH_SHORT);
		toast.show();



	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// TODO Auto-generated method stub
		//Log.d("hak", "onStartCommand");
		IntentFilter filter = new IntentFilter();
		filter.addAction("android.intent.action.UMS_DISCONNECTED");
		getApplicationContext().registerReceiver(receiver, filter);

		return super.onStartCommand(intent, flags, startId);
	}



	public void onDestroy() {
		Log.i("hak","Service Stop onDestroy");
		Toast toast = Toast.makeText(getApplicationContext(), "Service Stop",
				Toast.LENGTH_SHORT);
		toast.show();
		at.stop();
		mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
		try {
			Log.d("hak","socket close");
			mSocketServer.close();
			mClient.close();
			bis.close();
		} catch (IOException e) { ; }

		super.onDestroy();
		};

	@Override
	public boolean stopService(Intent name) {
		// TODO Auto-generated method stub
		Log.d("hak","StopService");
		at.stop();
		return super.stopService(name);
	}

	BroadcastReceiver receiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			Toast toast = Toast.makeText(getApplicationContext(), "receiver usb Stop",
					Toast.LENGTH_SHORT);
			Log.d("hak", " USB stop");
			if (intent.getAction().equalsIgnoreCase( "android.intent.action.UMS_DISCONNECTED"))
			{
				Log.d("hak", " USB unplugged stopping  service");
				stopSelf();
			}
		}
	};

	PhoneStateListener phoneStateListener = new PhoneStateListener(){
		public void onCallStateChanged(int state, String incomingNumber){
			String phoneNumber;
			int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
					AudioFormat.CHANNEL_CONFIGURATION_STEREO,
					AudioFormat.ENCODING_PCM_16BIT);
			Log.d("hak", "2");

			at = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
					AudioFormat.CHANNEL_CONFIGURATION_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
					AudioTrack.MODE_STREAM);
			switch(state){
			case TelephonyManager.CALL_STATE_IDLE:
				// not calling or ringing
				Log.i("speacall","STATE_IDLE");
				at.play();
				break;
			case TelephonyManager.CALL_STATE_RINGING:
				// ringing a call bell
				Log.i("speacall","STATE_ring" + incomingNumber);
				 phoneNumber = String.valueOf(incomingNumber);
//				try {
//					//server.mServerWriter.write("SPEACALL8"+phoneNumber+"\n");
//					server.mServerWriter.flush();
//				} catch (IOException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
				
				at.stop();
				break;
			case TelephonyManager.CALL_STATE_OFFHOOK:
				// calling
				Log.i("speacall","STATE_offhook");
				 phoneNumber = String.valueOf(incomingNumber);
//				try {
//					//server.mServerWriter.write("SPEACALL9"+phoneNumber+"\n");
//					server.mServerWriter.flush();
//				} catch (IOException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
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
				

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// 입 출력용 스트림 연결
			bis = new BufferedInputStream(is);
			//bos = new BufferedOutputStream(os);

			try {
				while (true) {
					waitHeader(bis);
					int type = getType(bis);
					int payloadLen = getPayloadLength(bis);
					switch (type) {
					case 0:
						try {
							// 소켓 종료
							mServerWriter.close();
							//mReaderFromClient.close();
							mSocketServer.close();
						} catch (Exception e) {
							e.printStackTrace();
						}
						stopSelf();
						//stopService(new Intent(getApplicationContext(), SpeacallService.class));
						break;
						// if audio data
					case 1:
						playSound(bis, payloadLen);
						break;
						// if contact
					case 2:
						getContactList();
						contactTask = new ContactTask();
						contactTask.execute();
						break;
						// if CallLog
					case 3:
						mCallListTask = new CallListTask();
						mCallListTask.execute();
						break;
					case 4:
						Log.d("hak", " start and payload len :  "+ payloadLen);
						getPhone( bis,  payloadLen);
						break;			
					default :
						break;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				// 소켓 종료
				mServerWriter.close();
				//mReaderFromClient.close();
				mSocketServer.close();
			} catch (Exception e) {
				;
			}

		}

		private void waitHeader(BufferedInputStream bis) {
			byte[] header = "SPEACALL".getBytes();
			int i = 0;
			while (i < header.length) {
				try {
					if (bis.read() == header[i])
						i++;
					else
						i = 0;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
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
			String msg = "";	
			while(readPayloadLen < payloadLen) {
				try {
					int readLen = bis.read(buffer, 0, (buffer.length < payloadLen) ? buffer.length : payloadLen - readPayloadLen);
					readPayloadLen += readLen;
					msg = new String(buffer,0,readLen);
					Log.d("hak", "this is  : "+msg +" & readLen : "+ readLen);
					
				} catch (IOException e) {
					// TODO Auto-generated catch block
					Log.d("hak","getphone is non working");
					e.printStackTrace();
				}
			}
			Log.d("hak", "message from pc : "+msg+"<-");
			Intent callIntent = new Intent(Intent.ACTION_CALL,Uri.parse("tel:"+msg));
			callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(callIntent);
		}

		public void endSocket()
		{
			try {
				// 소켓 종료
				mServerWriter.close();
				//mReaderFromClient.close();
				mSocketServer.close();
			} catch (Exception e) {
				e.printStackTrace();
			}

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
	

	private class AudioThread implements Runnable {
		public AudioThread() {
			// TODO Auto-generated constructor stub
		}

		public void run() {
			try {
				Log.d("hak", "audio in Background");

				int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
						AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT);

				at = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
						AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
						AudioTrack.MODE_STREAM);

				//at.play();

			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "audio thread is not working");
			}
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
				server.mServerWriter.write(listsize+"\n");
				server.mServerWriter.flush();
			//	Log.d("hak","speacall size : "+listsize);
				for (int i = 0; i < arContactList.size(); i++) {

					try{
						arGeneral.add(arContactList.get(i).phonenum);
						if  ((i <= listsize-2) && (arContactList.get(i).phonenum.equals(arContactList.get(i + 1).phonenum) || arContactList.get(i).phonenum.equals(arContactList.get(i + 2).phonenum)) ) {
							//mMessage = null;
							//Log.d("hak","speacall cnt : "+String.valueOf(cnt++) + " i : "+i);
							continue;
						}
					}catch (Exception e) {
						e.printStackTrace();
						Log.d("hak", "de-duplicate is not working");
					}


					String userName = arContactList.get(i).getName().toString();
					String userPhone = arContactList.get(i).phonenum.toString();
					mMessage = userPhone + " "
							+ userName;
					server.mServerWriter.write(mMessage+"\n");
					server.mServerWriter.flush();
					mMessage = null;
				}
				
				


			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "contact task is not working");
			}
			return null;
		}
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
				sb.append(" ").append(calltype);

				sb.append(" ").append(
						curCallLog.getString(curCallLog
								.getColumnIndex(CallLog.Calls.NUMBER)));
				sb.append(" ").append(callname);
				curCallLog.moveToNext();

				//String backupData = sb.toString();

				callcount++;
				Message temp_msg = Message.obtain();
				temp_msg.what = MSG_TO_CLIENT;
				temp_msg.obj = sb.toString();
				server.mMessageHandler.sendMessage(temp_msg);

				//Log.d("calllog", sb.toString());
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

	private String timeToString(Long time) {
		SimpleDateFormat simpleFormat = new SimpleDateFormat(
				"yyyy-MM-dd HH:mm:ss");
		String date = simpleFormat.format(new Date(time));
		return date;
	}

}
