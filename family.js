(function(){
'use strict';
const root=document.getElementById('familyWorkspace');
if(!root)return;
let busy=false;
let refreshTimer=null;
let realtimeRetryTimer=null;
const realtimeChannels=new Map();
function clearFamilyRealtime(){
 if(realtimeRetryTimer){clearTimeout(realtimeRetryTimer);realtimeRetryTimer=null;}
 for(const channel of realtimeChannels.values())supabaseClient.removeChannel(channel);
 realtimeChannels.clear();
}
function handleFamilyRealtimeStatus(key,channel,status,error){
 if(!['CHANNEL_ERROR','TIMED_OUT','CLOSED'].includes(status))return;
 if(realtimeChannels.get(key)!==channel)return;
 console.warn('Family realtime channel disconnected:',key,status,error||'');
 realtimeChannels.delete(key);
 supabaseClient.removeChannel(channel);
 if(!realtimeRetryTimer){
  realtimeRetryTimer=setTimeout(()=>{
   realtimeRetryTimer=null;
   if(currentUser)scheduleFamilyReload();
  },1500);
 }
}
function scheduleFamilyReload(){
 if(refreshTimer)clearTimeout(refreshTimer);
 refreshTimer=setTimeout(()=>{
  refreshTimer=null;
  if(busy){scheduleFamilyReload();return;}
  loadFamilyWorkspace();
 },300);
}
function syncFamilyRealtime(familyIds){
 if(!currentUser){clearFamilyRealtime();return;}
 const desired=new Set(['inbox']);
 for(const id of familyIds)desired.add('family:'+id);
 for(const [key,channel] of realtimeChannels){
  if(!desired.has(key)){supabaseClient.removeChannel(channel);realtimeChannels.delete(key);}
 }
 if(!realtimeChannels.has('inbox')){
  const channel=supabaseClient.channel('family-inbox-'+currentUser.id)
   .on('postgres_changes',{event:'*',schema:'public',table:'family_invites',filter:'invitee_id=eq.'+currentUser.id},scheduleFamilyReload)
   .subscribe((status,error)=>handleFamilyRealtimeStatus('inbox',channel,status,error));
  realtimeChannels.set('inbox',channel);
 }
 for(const id of familyIds){
  const key='family:'+id;
  if(realtimeChannels.has(key))continue;
  const channel=supabaseClient.channel('family-workspace-'+id+'-'+currentUser.id)
   .on('postgres_changes',{event:'*',schema:'public',table:'family_messages',filter:'family_id=eq.'+id},scheduleFamilyReload)
   .on('postgres_changes',{event:'*',schema:'public',table:'family_members',filter:'family_id=eq.'+id},scheduleFamilyReload)
   .on('postgres_changes',{event:'*',schema:'public',table:'family_invites',filter:'family_id=eq.'+id},scheduleFamilyReload)
   .on('postgres_changes',{event:'*',schema:'public',table:'families',filter:'id=eq.'+id},scheduleFamilyReload)
   .subscribe((status,error)=>handleFamilyRealtimeStatus(key,channel,status,error));
  realtimeChannels.set(key,channel);
 }
}
const make=(tag,cls,text)=>{const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;};
const btn=(label,cls,fn)=>{const node=make('button',cls||'btn',label);node.type='button';node.addEventListener('click',fn);return node;};
async function rpc(name,args){const result=await supabaseClient.rpc(name,args);if(result.error)throw result.error;return result.data;}
function loggedOut(){
 root.replaceChildren(make('p','sub','Sign in to create a Family, accept invitations and chat with members.'));
 root.append(btn('Log in','btn primary',()=>openAuthModal('login')));
}
async function loadFamilyWorkspace(){
 if(busy)return;busy=true;
 try{
  if(!currentUser){clearFamilyRealtime();loggedOut();return;}
  root.replaceChildren(make('p','sub','Loading Family workspace…'));
  const [mine,inbox]=await Promise.all([
   supabaseClient.from('family_members').select('family_id,role,joined_at,families(id,name,owner_id,created_at)').eq('user_id',currentUser.id).order('joined_at',{ascending:true}),
   supabaseClient.from('family_invites').select('id,family_id,inviter_id,created_at,families(name)').eq('invitee_id',currentUser.id).eq('status','pending').order('created_at',{ascending:false})
  ]);
  if(mine.error)throw mine.error;if(inbox.error)throw inbox.error;
  syncFamilyRealtime((mine.data||[]).map(item=>item.family_id));
  root.replaceChildren();
  const intro=make('p','sub','Create a private Family/Clan, invite players by username, manage members and chat. Each Family supports up to 50 members.');
  root.append(intro);
  const createForm=document.createElement('form');createForm.className='family-create';
  const name=make('input');name.placeholder='Family / Clan name';name.maxLength=40;name.minLength=3;name.required=true;
  const create=make('button','btn primary','Create Family');create.type='submit';createForm.append(name,create);
  createForm.onsubmit=async event=>{event.preventDefault();create.disabled=true;try{await rpc('create_family',{p_name:name.value});toast('Family created.');await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not create Family.');}finally{create.disabled=false;}};
  root.append(createForm);
  const invites=make('div','family-invites');invites.append(make('h3',null,'Pending invitations'));
  if(!inbox.data?.length)invites.append(make('p','sub','No pending invitations.'));
  for(const invite of inbox.data||[]){
   const row=make('div','family-invite-card');
   row.append(make('span',null,(invite.families?.name||'Family')+' · invitation received'));
   const accept=btn('Accept','btn primary smallbtn',async()=>{accept.disabled=true;try{await rpc('respond_family_invite',{p_invite_id:invite.id,p_accept:true});toast('You joined the Family.');await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not accept invitation.');accept.disabled=false;}});
   const decline=btn('Decline','btn smallbtn',async()=>{decline.disabled=true;try{await rpc('respond_family_invite',{p_invite_id:invite.id,p_accept:false});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not decline invitation.');decline.disabled=false;}});
   row.append(accept,decline);invites.append(row);
  }
  root.append(invites);
  const families=make('div','family-list');families.append(make('h3',null,'Your Families'));
  if(!mine.data?.length)families.append(make('p','sub','You are not in a Family yet. Create one or accept an invitation.'));
  for(const membership of mine.data||[]){
   const family=membership.families;if(!family)continue;
   const card=make('article','family-card');
   const head=make('div','family-card-head');head.append(make('h3',null,'♟ '+family.name),make('span','family-role',membership.role.toUpperCase()));card.append(head);
   if(['owner','admin'].includes(membership.role)){
    const inviteForm=document.createElement('form');inviteForm.className='family-invite-form';
    const username=make('input');username.placeholder='Invite by exact username';username.maxLength=40;username.required=true;
    const sendInvite=make('button','btn smallbtn','Invite player');sendInvite.type='submit';inviteForm.append(username,sendInvite);
    inviteForm.onsubmit=async event=>{event.preventDefault();sendInvite.disabled=true;try{await rpc('invite_family_member',{p_family_id:family.id,p_username:username.value});username.value='';toast('Invitation sent.');}catch(error){toast(error.message||'Invitation failed.');}finally{sendInvite.disabled=false;}};
    card.append(inviteForm);
    if(membership.role==='owner')card.append(btn('Delete Family','btn smallbtn',async()=>{if(!confirm('Delete '+family.name+' and its chat/invitations for all members?'))return;try{await rpc('delete_family',{p_family_id:family.id});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not delete Family.');}}));
   }else{
    card.append(btn('Leave Family','btn smallbtn',async()=>{if(!confirm('Leave '+family.name+'?'))return;try{await rpc('leave_family',{p_family_id:family.id});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not leave Family.');}}));
   }
   const members=await supabaseClient.from('family_members').select('user_id,role,joined_at').eq('family_id',family.id).order('joined_at',{ascending:true});
   const memberBox=make('div','family-members');memberBox.append(make('h4',null,'Members ('+(members.data?.length||0)+'/50)'));
   if(members.error)memberBox.append(make('p','sub',members.error.message));
   const memberIds=(members.data||[]).map(item=>item.user_id);
   const profileRows=memberIds.length?await supabaseClient.from('profiles').select('id,username,full_name').in('id',memberIds):{data:[],error:null};
   const profiles=new Map((profileRows.data||[]).map(profile=>[profile.id,profile]));
   for(const member of members.data||[]){
    const profile=profiles.get(member.user_id)||{};
    const display=profile.username||profile.full_name||member.user_id.slice(0,8);
    const line=make('div','family-member-row');line.append(make('span',null,display+' · '+member.role));
    const canRemove=['owner','admin'].includes(membership.role)&&member.role!=='owner'&&(membership.role==='owner'||member.role==='member');
    if(membership.role==='owner'&&member.role==='member')line.append(btn('Make admin','textbtn',async()=>{try{await rpc('set_family_member_role',{p_family_id:family.id,p_user_id:member.user_id,p_role:'admin'});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not update role.');}}));
    if(membership.role==='owner'&&member.role==='admin')line.append(btn('Remove admin','textbtn',async()=>{try{await rpc('set_family_member_role',{p_family_id:family.id,p_user_id:member.user_id,p_role:'member'});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not update role.');}}));
    if(canRemove)line.append(btn('Remove','textbtn',async()=>{if(!confirm('Remove '+display+' from this Family?'))return;try{await rpc('remove_family_member',{p_family_id:family.id,p_user_id:member.user_id});await loadFamilyWorkspace();}catch(error){toast(error.message||'Could not remove member.');}}));
    memberBox.append(line);
   }
   card.append(memberBox);
   const chat=make('div','family-chat');chat.append(make('h4',null,'Family Chat'));
   const log=make('div','family-chat-log');
   const messages=await supabaseClient.from('family_messages').select('id,user_id,message,created_at').eq('family_id',family.id).order('created_at',{ascending:false}).limit(50);
   if(messages.error)log.append(make('p','sub',messages.error.message));
   else if(!messages.data?.length)log.append(make('p','sub','No messages yet. Start the conversation.'));
   else{
    const ids=[...new Set(messages.data.map(item=>item.user_id))];
    const people=ids.length?await supabaseClient.from('profiles').select('id,username').in('id',ids):{data:[]};
    const names=new Map((people.data||[]).map(person=>[person.id,person.username||'Player']));
    for(const message of [...messages.data].reverse())log.append(make('p','family-message',(message.user_id===currentUser.id?'You':(names.get(message.user_id)||'Player'))+': '+message.message));
   }
   chat.append(log);
   const chatForm=document.createElement('form');chatForm.className='family-chat-form';
   const messageInput=make('input');messageInput.placeholder='Write to your Family…';messageInput.maxLength=1000;messageInput.required=true;
   const send=make('button','btn primary','Send');send.type='submit';chatForm.append(messageInput,send);
   chatForm.onsubmit=async event=>{event.preventDefault();send.disabled=true;try{const saved=await supabaseClient.from('family_messages').insert({family_id:family.id,user_id:currentUser.id,message:messageInput.value.trim()});if(saved.error)throw saved.error;messageInput.value='';await loadFamilyWorkspace();}catch(error){toast(error.message||'Message could not be sent.');}finally{send.disabled=false;}};
   chat.append(chatForm);card.append(chat);families.append(card);
  }
  root.append(families);
 }catch(error){root.replaceChildren(make('p','sub','Family workspace error: '+(error.message||'Unknown error')));}
 finally{busy=false;}
}
document.getElementById('familyRefreshBtn')?.addEventListener('click',loadFamilyWorkspace);
const originalRefreshSession=refreshSession;
refreshSession=async function(){await originalRefreshSession();await loadFamilyWorkspace();};
supabaseClient.auth.onAuthStateChange((event,session)=>{
 if(!['SIGNED_IN','SIGNED_OUT','TOKEN_REFRESHED','INITIAL_SESSION','USER_UPDATED'].includes(event))return;
 currentUser=session?.user||null;
 if(event==='SIGNED_OUT')clearFamilyRealtime();
 setTimeout(loadFamilyWorkspace,0);
});
supabaseClient.auth.getSession().then(({data})=>{
 currentUser=data?.session?.user||null;
 loadFamilyWorkspace();
});
})();