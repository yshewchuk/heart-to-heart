package com.yurishewchuk.hearttoheart.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import com.yurishewchuk.hearttoheart.data.HeartMessage
import com.yurishewchuk.hearttoheart.data.MessageCategory
import com.yurishewchuk.hearttoheart.data.Partner
import com.yurishewchuk.hearttoheart.data.PartnerPrefs
import com.yurishewchuk.hearttoheart.data.PairingRepository.UserAccountEntry
import com.yurishewchuk.hearttoheart.data.StoredMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yurishewchuk.hearttoheart.ui.components.CategoryButton
import com.yurishewchuk.hearttoheart.ui.theme.Coral
import androidx.compose.material.icons.filled.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    accounts: Map<String, UserAccountEntry>,
    selectedAccountUid: String?,
    selectedPartnerPrefs: PartnerPrefs = PartnerPrefs(),
    lastReceivedMessage: StoredMessage? = null,
    onSendMessage: (HeartMessage) -> Unit,
    onSelectAccount: (String) -> Unit,
    onPairClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<MessageCategory?>(null) }
    var noteText by remember { mutableStateOf("") }
    var showNoteInput by remember { mutableStateOf(false) }
    
    val selectedAccount = selectedAccountUid?.let { accounts[it] }
    val partner = selectedAccount?.pairedPartnerUid?.let { partnerUid ->
        Partner(
            uid = partnerUid,
            fcmToken = "",
            displayName = selectedAccount.displayName ?: "My Love",
            pairedAt = selectedAccount.pairedAt ?: 0L,
            encryptionKey = selectedAccount.encryptionKey
        )
    }
    val pairedAccounts = remember(accounts) {
        accounts.values.filter { it.pairedPartnerUid != null }.sortedBy { it.anonymousUid }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Coral,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Heart-to-Heart",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (pairedAccounts.size < 10) {
                        IconButton(onClick = onPairClick) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = if (pairedAccounts.isEmpty()) "Pair with Partner" else "Pair with New User"
                            )
                        }
                    }
                    IconButton(onClick = onHistoryClick) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History"
                        )
                    }
                    if (pairedAccounts.isNotEmpty()) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val backgroundColor = MaterialTheme.colorScheme.background
        val scrollState = rememberScrollState()
        
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(backgroundColor, Coral.copy(alpha = 0.08f))
                    )
                )
                .padding(padding)
        ) {
            val isCompactHeight = maxHeight < 700.dp
            val profileSize = if (isCompactHeight) 80.dp else 120.dp
            val topSpacing = if (isCompactHeight) 8.dp else 24.dp
            val sectionSpacing = if (isCompactHeight) 16.dp else 40.dp
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(topSpacing))

                if (pairedAccounts.isNotEmpty()) {
                    PartnerSelectorStrip(
                        accounts = pairedAccounts,
                        selectedAccountUid = selectedAccountUid,
                        selectedPartnerPrefs = selectedPartnerPrefs,
                        onSelectAccount = onSelectAccount
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (pairedAccounts.size < 10) {
                        TextButton(onClick = onPairClick) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair with New User")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // Partner Avatar
                if (partner != null) {
                    PartnerAvatar(partner = partner, prefs = selectedPartnerPrefs, size = profileSize)
                } else {
                    NoPairPrompt(onPairClick = onPairClick, size = profileSize)
                }
                
                // Last Received Message
                lastReceivedMessage?.let { message ->
                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 16.dp))
                    LastReceivedCard(message = message)
                }
                
                Spacer(modifier = Modifier.height(sectionSpacing))
            
            // Category Selection
            Text(
                text = "Send a signal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Ring of Category Buttons
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                MessageCategory.entries.forEach { category ->
                    CategoryButton(
                        category = category,
                        isSelected = selectedCategory == category,
                        onSelect = { 
                            selectedCategory = category
                            showNoteInput = true
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Note Input (appears when category selected)
            AnimatedVisibility(
                visible = showNoteInput && selectedCategory != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { if (it.length <= 140) noteText = it },
                        label = { Text("Add a note (optional)") },
                        placeholder = { Text("What's on your mind?") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            cursorColor = Coral
                        ),
                        supportingText = {
                            Text("${noteText.length}/140")
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Send Button
                    Button(
                        onClick = {
                            selectedCategory?.let { category ->
                                onSendMessage(HeartMessage(category = category, note = noteText))
                                noteText = ""
                                selectedCategory = null
                                showNoteInput = false
                            }
                        },
                        enabled = selectedCategory != null && partner != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Coral
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Send ${selectedCategory?.displayName ?: ""}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Show recipient info or pairing prompt
                    Spacer(modifier = Modifier.height(8.dp))
                    if (partner != null) {
                        val recipientName = selectedPartnerPrefs.nickname.ifBlank { partner.displayName }
                        Text(
                            text = "Will send to $recipientName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "Pair with your partner to start sending signals",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun PartnerAvatar(
    partner: Partner,
    prefs: PartnerPrefs = PartnerPrefs(),
    size: androidx.compose.ui.unit.Dp = 120.dp
) {
    val iconSize = size * 0.53f  // Proportional icon size
    val displayName = prefs.nickname.ifBlank { partner.displayName }
    // Profile picture can be a file path or URI
    val profilePicture: Any? = prefs.profilePictureUri?.let { path ->
        if (path.startsWith("/")) java.io.File(path) else Uri.parse(path)
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = Coral,
            shadowElevation = 8.dp,
            modifier = Modifier.size(size)
        ) {
            if (profilePicture != null) {
                Image(
                    painter = rememberAsyncImagePainter(profilePicture),
                    contentDescription = "Partner photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(if (size < 100.dp) 8.dp else 16.dp))
        
        Text(
            text = displayName,
            style = if (size < 100.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = "Connected ❤️",
            style = MaterialTheme.typography.bodyMedium,
            color = Coral
        )
    }
}

@Composable
private fun NoPairPrompt(onPairClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 120.dp) {
    val emojiSize = if (size < 100.dp) 32.sp else 48.sp
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = Coral.copy(alpha = 0.2f),
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "💕",
                    fontSize = emojiSize
                )
            }
        }
        
        Spacer(modifier = Modifier.height(if (size < 100.dp) 8.dp else 16.dp))
        
        Text(
            text = "Get Started",
            style = if (size < 100.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = "Install on your partner's device and pair to connect",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(if (size < 100.dp) 8.dp else 16.dp))
        
        Button(
            onClick = onPairClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Coral
            )
        ) {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pair with Partner")
        }
    }
}

@Composable
private fun LastReceivedCard(message: StoredMessage) {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    val now = System.currentTimeMillis()
    val isToday = (now - message.timestamp) < 24 * 60 * 60 * 1000
    
    val timeString = if (isToday) {
        "Today at ${timeFormat.format(Date(message.timestamp))}"
    } else {
        "${dateFormat.format(Date(message.timestamp))} at ${timeFormat.format(Date(message.timestamp))}"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Coral.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji
            Text(
                text = message.category.emoji,
                fontSize = 32.sp
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Last received",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                
                Text(
                    text = message.category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Coral
                )
                
                if (message.note.isNotBlank()) {
                    Text(
                        text = "\"${message.note}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
                
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun PartnerSelectorStrip(
    accounts: List<UserAccountEntry>,
    selectedAccountUid: String?,
    selectedPartnerPrefs: PartnerPrefs,
    onSelectAccount: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        items(accounts, key = { it.anonymousUid }) { account ->
            val isSelected = selectedAccountUid == account.anonymousUid
            val label = if (isSelected) {
                selectedPartnerPrefs.nickname.ifBlank { account.displayName ?: "Partner" }
            } else {
                account.displayName ?: "Partner"
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Coral.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.clickable { onSelectAccount(account.anonymousUid) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "❤️")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
