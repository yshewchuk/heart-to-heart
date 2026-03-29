package com.hearttoheart.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.hearttoheart.app.data.NICKNAME_PRESETS
import com.hearttoheart.app.data.NotificationIcon
import com.hearttoheart.app.data.PairingRepository
import com.hearttoheart.app.data.PartnerPreferencesRepository
import com.hearttoheart.app.data.PartnerPrefs
import com.hearttoheart.app.ui.theme.Coral
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accounts: Map<String, PairingRepository.UserAccountEntry>,
    selectedAccountUid: String?,
    currentPrefs: PartnerPrefs,
    onNavigateBack: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onPrefsUpdated: () -> Unit,
    onUnpair: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { PartnerPreferencesRepository(context) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val pairedAccounts = remember(accounts) {
        accounts.values.filter { it.pairedPartnerUid != null }.sortedBy { it.anonymousUid }
    }
    val activeAccountUid = remember(selectedAccountUid, pairedAccounts) {
        selectedAccountUid ?: pairedAccounts.firstOrNull()?.anonymousUid
    }
    
    var nickname by remember(currentPrefs, activeAccountUid) { mutableStateOf(currentPrefs.nickname) }
    var customNickname by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var selectedIcon by remember(currentPrefs) { mutableStateOf(currentPrefs.notificationIcon) }
    // Profile picture can be a file path or URI
    var profilePicturePath by remember(currentPrefs) { mutableStateOf(currentPrefs.profilePictureUri) }
    var tempSelectedUri by remember { mutableStateOf<Uri?>(null) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && activeAccountUid != null) {
            tempSelectedUri = uri // Show immediately while saving
            scope.launch {
                repository.setProfilePicture(activeAccountUid, uri)
                // After saving, the path will be updated via the prefs flow
                tempSelectedUri = null
                onPrefsUpdated()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partner Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val backgroundColor = MaterialTheme.colorScheme.background
        
        // Determine what to display: temp selection, saved file path, or nothing
        val imageToDisplay: Any? = tempSelectedUri ?: profilePicturePath?.let { path ->
            if (path.startsWith("/")) java.io.File(path) else Uri.parse(path)
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(backgroundColor, Coral.copy(alpha = 0.08f))
                    )
                )
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (pairedAccounts.size > 1) {
                Text(
                    text = "Select Partner",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(pairedAccounts) { account ->
                        val isSelected = account.anonymousUid == activeAccountUid
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectAccount(account.anonymousUid) },
                            label = { Text(account.displayName ?: "Partner") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Coral,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Profile Picture Section
            Text(
                text = "Partner's Photo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Coral)
                    .clickable { imagePickerLauncher.launch("image/*") }
            ) {
                if (imageToDisplay != null) {
                    Image(
                        painter = rememberAsyncImagePainter(imageToDisplay),
                        contentDescription = "Partner photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(64.dp)
                    )
                }
                
                // Edit overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(2.dp, Coral, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Change photo",
                        tint = Coral,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            TextButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Text("Choose photo", color = Coral)
            }
            
            if (imageToDisplay != null) {
                TextButton(onClick = {
                    profilePicturePath = null
                    tempSelectedUri = null
                    scope.launch {
                        if (activeAccountUid != null) {
                            repository.setProfilePicture(activeAccountUid, null)
                        }
                        onPrefsUpdated()
                    }
                }) {
                    Text("Remove photo", color = MaterialTheme.colorScheme.error)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Nickname Section
            Text(
                text = "Partner's Nickname",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Preset nicknames
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(NICKNAME_PRESETS) { (presetName, emoji) ->
                    val isSelected = nickname == presetName && !showCustomInput
                    
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            nickname = presetName
                            showCustomInput = false
                            scope.launch {
                                if (activeAccountUid != null) {
                                    repository.setNickname(activeAccountUid, presetName)
                                }
                                onPrefsUpdated()
                            }
                        },
                        label = { Text("$emoji $presetName") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Coral,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                
                // Custom option
                item {
                    FilterChip(
                        selected = showCustomInput,
                        onClick = { showCustomInput = true },
                        label = { Text("✏️ Custom") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Coral,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            
            // Custom nickname input
            if (showCustomInput) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = customNickname,
                        onValueChange = { if (it.length <= 20) customNickname = it },
                        placeholder = { Text("Enter custom nickname") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            cursorColor = Coral
                        )
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (customNickname.isNotBlank()) {
                                nickname = customNickname
                                scope.launch {
                                    if (activeAccountUid != null) {
                                        repository.setNickname(activeAccountUid, customNickname)
                                    }
                                    onPrefsUpdated()
                                }
                            }
                        },
                        enabled = customNickname.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save",
                            tint = if (customNickname.isNotBlank()) Coral else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Notification Icon Section
            Text(
                text = "Notification Icon",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Text(
                text = "Choose the icon shown in notifications",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Icon grid
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                NotificationIcon.entries.forEach { icon ->
                    val isSelected = selectedIcon == icon
                    
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Coral else MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = if (isSelected) 4.dp else 0.dp,
                        modifier = Modifier
                            .size(56.dp)
                            .clickable {
                                selectedIcon = icon
                                scope.launch {
                                    if (activeAccountUid != null) {
                                        repository.setNotificationIcon(activeAccountUid, icon)
                                    }
                                    onPrefsUpdated()
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = icon.emoji,
                                fontSize = 28.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Selected: ${selectedIcon.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Current settings summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Current Settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💕 Nickname: $nickname",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${selectedIcon.emoji} Notification icon: ${selectedIcon.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "📷 Photo: ${if (imageToDisplay != null) "Custom" else "Default"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Unpair section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Disconnect",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This will remove your connection with your partner. You'll need to pair again to send messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showUnpairDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("Unpair")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Unpair confirmation dialog
    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair from partner?") },
            text = { 
                Text("This will disconnect you from your partner. You'll need to scan each other's QR codes again to reconnect.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (activeAccountUid == null) {
                            showUnpairDialog = false
                            return@TextButton
                        }
                        scope.launch {
                            repository.clearPreferences(activeAccountUid)
                            showUnpairDialog = false
                            onUnpair(activeAccountUid)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
