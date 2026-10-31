package com.snapclean.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jsoup.Jsoup

data class FriendRequest(val username:String,val display:String,val date:String)
class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  setContent {
   var requests by remember { mutableStateOf(listOf<FriendRequest>()) }
   var query by remember { mutableStateOf("") }
   val prefs = remember { getSharedPreferences("statuses",Context.MODE_PRIVATE) }
   var version by remember { mutableIntStateOf(0) }
   val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
    if(uri!=null){try{
     val html=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()} ?: ""
     val doc=Jsoup.parse(html)
     val header=doc.select("h3").firstOrNull{it.text().contains("Gönderilen Arkadaşlık İstekleri")}
     val table=header?.nextElementSibling()
     requests=table?.select("tr")?.drop(1)?.mapNotNull{tr->
      val cols=tr.select("td")
      if(cols.size>=3) FriendRequest(cols[0].text(),cols[1].text(),cols[2].text()) else null
     }?.distinctBy{it.username} ?: emptyList()
    }catch(e:Exception){requests=emptyList()}}
   }
   MaterialTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)){
     Text("SnapClean",style=MaterialTheme.typography.headlineMedium)
     Text("Yerel inceleme • Snapchat hesabına erişmez")
     Spacer(Modifier.height(12.dp))
     Button(onClick={picker.launch("text/html")}){Text("friends.html içe aktar")}
     Text("Kayıt: ${requests.size}")
     OutlinedTextField(value=query,onValueChange={query=it},label={Text("Kullanıcı ara")},modifier=Modifier.fillMaxWidth())
     LazyColumn{
      items(requests.filter{it.username.contains(query,true)||it.display.contains(query,true)}){item->
       val done=prefs.getBoolean(item.username,false)
       Column(Modifier.fillMaxWidth().padding(vertical=8.dp)){
        Text(item.display+" (@"+item.username+")")
        Text(item.date,style=MaterialTheme.typography.bodySmall)
        Row{
         TextButton(onClick={
          val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(ClipData.newPlainText("username",item.username))
         }){Text("Kopyala")}
         TextButton(onClick={prefs.edit().putBoolean(item.username,!done).apply();version++}){Text(if(done)"Geri al" else "İptal edildi (manuel)")}
        }
       }
       HorizontalDivider()
      }
     }
    }
   }
  }
 }
}
