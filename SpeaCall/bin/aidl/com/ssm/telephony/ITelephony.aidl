package com.ssm.telephony; 
  
import android.os.Bundle; 
    interface ITelephony { 
        boolean endCall(); 
     void dial(String number); 
    void answerRingingCall(); 
    }