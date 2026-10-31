// 仅表达客户端直传及副本状态；定时器不是实际传输、落盘或安全验证。
const mediaTimers = new Set();
const MEDIA_QUOTA = 10 * 1024 ** 3;

function peerMode(context) {
  if (state.peerMode) return state.peerMode;
  const members = context.scope === 'room' ? rooms.find(room => room.id === context.id)?.members.map(member => member.name) : groups.find(group => group.id === context.id)?.members;
  return members?.some(name => name !== me && people[name]?.online) ? 'online' : 'offline';
}

function deliveryState(context) {
  return peerMode(context) === 'offline' ? 'pending' : peerMode(context) === 'partial' ? 'partial' : 'received';
}

function syncNotice(scope) {
  const context = messageContext(scope), unavailable = peerMode(context) === 'offline';
  return `<div class="sync-notice" role="status"><span>${unavailable ? '无在线可达副本 · 缺失内容暂不可用' : peerMode(context) === 'partial' ? '部分成员无法直连 · 仅向可达成员同步' : '客户端保存 · 在线成员间同步'}</span><button class="text-button" data-action="sync-history" data-scope="${scope}">重试同步</button></div>`;
}

function messageReceipt(message) {
  const labels = { pending: '本机保存 · 待同步', received: '对端已接收', partial: '部分送达 · 其他成员待同步' };
  return message.delivery ? `<small class="message-receipt">${labels[message.delivery]}（模拟）</small>` : '';
}

function syncPeerMessages(scope) {
  const context = messageContext(scope), error = messageAccess(context);
  if (error || state.service === 'offline') { toast(error || '无法取得当前授权，请恢复服务后重试。'); return; }
  if (scope === 'room' && state.network !== 'connected' || peerMode(context) === 'offline') {
    toast('无在线可达副本，缺失内容暂不可用；本机内容继续保留。'); return;
  }
  for (const message of messageList(context) || []) {
    if (!message.deleted && ['pending', 'partial'].includes(message.delivery)) message.delivery = deliveryState(context);
  }
  render(); toast('已模拟同步到可达成员；未实现真实传输或落盘。');
}

document.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (button?.dataset.action === 'sync-history') syncPeerMessages(button.dataset.scope);
  if (button?.dataset.action === 'review-peer-mode' && reviewEnabled) {
    state.peerMode = button.dataset.mode; closeModal(); render();
    toast('副本可达性已切换（模拟）。');
  }
});

function messageContext(scope) {
  const target = scope === 'room' ? currentRoom() : currentGroup();
  return { scope, id: target?.id, groupId: scope === 'room' ? target?.groupId : target?.id, epoch: state.epoch };
}

function messageAccess(context) {
  if (!state.authenticated || state.verified === false || !isGroupMember(context.groupId)) return '当前已无消息访问权限。';
  if (context.scope === 'room' && (!state.joined || state.recovery || state.roomId !== context.id || state.epoch !== context.epoch)) return '房间活动已改变，请重新加入后发送。';
  if (context.scope === 'group' && !groups.some(group => group.id === context.id)) return '群组已不可用。';
  return '';
}

function messageList(context) {
  return (context.scope === 'room' ? rooms : groups).find(item => item.id === context.id)?.messages;
}

function groupMediaBytes(groupId) {
  const group = groups.find(item => item.id === groupId);
  return (group?.mediaUsedBytes || 0) + [...(group?.messages || []), ...rooms.filter(room => room.groupId === groupId).flatMap(room => room.messages)]
    .reduce((sum, message) => sum + (!message.deleted ? message.media?.size || 0 : 0), 0);
}

function clearMessageMedia(messages) {
  messages.forEach(message => { if (message.media?.url) URL.revokeObjectURL(message.media.url); });
}

function discardMediaDraft() {
  mediaTimers.forEach(clearTimeout);
  mediaTimers.clear();
  if (state.pendingMedia?.url) URL.revokeObjectURL(state.pendingMedia.url);
  state.pendingMedia = null;
  state.mediaDraft = null;
}

function openMediaDraft(scope) {
  const context = messageContext(scope), error = messageAccess(context);
  if (error) { toast(error); return; }
  discardMediaDraft();
  state.mediaDraft = { ...context, status: 'empty', progress: 0, createdAt: Date.now(), caption: '', error: '' };
  openModal('分享图片或视频', '模拟客户端直传 · 文件仅留在此页面，不会上传服务器或真实传输。', `<div class="field"><label for="media-file">图片 / 视频</label><input id="media-file" type="file" accept="image/jpeg,image/png,image/webp,video/mp4" data-change="media-file"><small>图片 ≤10 MiB；MP4 ≤100 MiB、2 分钟、1080p</small></div><div id="media-preview" class="media-preview"><span>尚未选择文件</span></div><div class="media-transfer" id="media-transfer" role="status" aria-live="polite"></div><div class="field"><label for="media-caption">附一句话（可选）</label><input id="media-caption" maxlength="8000" placeholder="说说这个精彩瞬间…"></div>${reviewEnabled ? `<details class="media-review"><summary>直传情景复核（模拟）</summary>${[['offline','传输失败'],['quota','逻辑配额已满'],['expired','草稿过期'],['revoked','发送前失权'],['invalid','接收端校验失败']].map(([outcome, label]) => `<button class="button small" data-action="review-media-outcome" data-outcome="${outcome}">${label}</button>`).join('')}</details>` : ''}`, `<button class="button" data-action="modal-close">取消</button><button class="button primary" data-action="confirm-media" disabled>模拟直传并发送</button>`);
  updateMediaTransfer();
}

function mediaError(message, status = 'failed') {
  if (!state.mediaDraft) return;
  state.mediaDraft.status = status;
  state.mediaDraft.error = message;
  updateMediaTransfer();
}

function updateMediaTransfer() {
  const draft = state.mediaDraft, status = document.getElementById('media-transfer');
  if (!draft || !status) return;
  const busy = ['uploading', 'checking', 'sending'].includes(draft.status);
  const noPeer = peerMode(draft) === 'offline';
  const labels = { empty: '请选择文件', selected: '待发送', uploading: noPeer ? '模拟本机保存中' : '模拟客户端直传中', checking: noPeer ? '模拟本机校验中' : '模拟接收端校验中', sending: noPeer ? '模拟登记待同步消息' : '模拟等待对端落盘确认', failed: '发送失败', revoked: '访问已撤销', expired: '草稿已过期', invalid: '文件不可用' };
  status.innerHTML = `<strong>${labels[draft.status] || draft.status}</strong>${state.pendingMedia ? `<span class="media-filename">${esc(state.pendingMedia.name)}</span>` : ''}${busy ? `<progress max="100" value="${draft.progress}" aria-label="模拟上传进度"></progress><span>${draft.progress}%</span>` : ''}${draft.error ? `<p class="media-error">${esc(draft.error)}</p>` : ''}`;
  const file = document.getElementById('media-file'), caption = document.getElementById('media-caption');
  if (file) file.disabled = busy;
  if (caption) caption.disabled = busy;
  const send = document.querySelector('[data-action=confirm-media],[data-action=retry-media]');
  if (send) {
    send.disabled = busy || !state.pendingMedia || ['revoked', 'expired', 'invalid'].includes(draft.status);
    send.dataset.action = draft.status === 'failed' ? 'retry-media' : 'confirm-media';
    send.textContent = draft.status === 'failed' ? '重试发送' : busy ? '正在处理…' : '模拟直传并发送';
    send.setAttribute('aria-busy', String(busy));
  }
}

async function selectMediaFile(file) {
  const draft = state.mediaDraft;
  if (!draft || !file) return;
  const selection = draft.selection = (draft.selection || 0) + 1;
  if (state.pendingMedia?.url) URL.revokeObjectURL(state.pendingMedia.url);
  state.pendingMedia = null;
  draft.error = '';
  document.getElementById('media-preview').textContent = '正在检查本地文件…';
  const image = ['image/jpeg', 'image/png', 'image/webp'].includes(file.type);
  if (!image && file.type !== 'video/mp4') { mediaError('请选择 JPEG、PNG、WebP 图片或 MP4 视频。SVG 和动画图片不支持。', 'invalid'); return; }
  if (!file.size || file.size > (image ? 10 : 100) * 1024 ** 2) { mediaError(image ? '图片须大于 0 且不超过 10 MiB。' : '视频须大于 0 且不超过 100 MiB。', 'invalid'); return; }
  const media = { type: image ? 'image' : 'video', url: URL.createObjectURL(file), name: file.name, size: file.size };
  const element = image ? new Image() : document.createElement('video');
  const valid = await new Promise(resolve => {
    const timer = setTimeout(() => resolve(false), 4000);
    element[image ? 'onload' : 'onloadedmetadata'] = () => { clearTimeout(timer); resolve(true); };
    element.onerror = () => { clearTimeout(timer); resolve(false); };
    if (!image) element.preload = 'metadata';
    element.src = media.url;
  });
  if (state.mediaDraft !== draft || draft.selection !== selection) { URL.revokeObjectURL(media.url); return; }
  if (!valid || image && element.naturalWidth * element.naturalHeight > 20000000 || !image && (!Number.isFinite(element.duration) || element.duration > 120 || element.videoWidth > 1920 || element.videoHeight > 1080)) {
    URL.revokeObjectURL(media.url);
    mediaError(valid ? '图片超过 2000 万像素，或视频超过 2 分钟 / 1080p。' : '文件无法预览，请重新选择支持的文件。', 'invalid');
    return;
  }
  state.pendingMedia = media;
  draft.status = 'selected';
  draft.createdAt = Date.now();
  document.getElementById('media-preview').innerHTML = mediaPreview(media);
  updateMediaTransfer();
}

function scheduleMedia(draft, callback, delay) {
  const timer = setTimeout(() => {
    mediaTimers.delete(timer);
    if (state.mediaDraft === draft) callback();
  }, delay);
  mediaTimers.add(timer);
}

function startMediaDelivery() {
  const draft = state.mediaDraft, media = state.pendingMedia;
  if (!draft || !media || ['uploading', 'checking', 'sending', 'expired', 'revoked', 'invalid'].includes(draft.status)) return;
  const error = messageAccess(draft);
  if (error) { mediaError(error, 'revoked'); return; }
  if (draft.scope === 'room' && state.network !== 'connected') { mediaError('虚拟网络未就绪，请恢复连接后重试。'); return; }
  if (Date.now() - draft.createdAt >= 15 * 60 * 1000) { mediaError('草稿已过期，请重新选择文件。', 'expired'); return; }
  draft.caption = document.getElementById('media-caption').value.trim();
  if ([...draft.caption].length > 4000) { mediaError('附言不可超过 4000 个字符。'); return; }
  draft.error = '';
  draft.progress = 0;
  draft.status = 'uploading';
  const outcome = reviewEnabled ? state.mediaReviewOutcome : '';
  state.mediaReviewOutcome = '';
  updateMediaTransfer();
  const advance = () => {
    if (messageAccess(draft)) { mediaError('资格已失效，此附件没有发送。', 'revoked'); return; }
    if (state.service === 'offline' || outcome === 'offline') { mediaError('直传中断，请检查网络后重试。'); return; }
    if (outcome === 'expired') { mediaError('草稿已过期，请重新选择文件。', 'expired'); return; }
    if (outcome === 'quota' || groupMediaBytes(draft.groupId) + media.size > MEDIA_QUOTA) { mediaError('此群附件逻辑配额已满（10 GiB），删除不再需要的消息后重试。'); return; }
    draft.progress += 25;
    if (draft.progress < 100) { updateMediaTransfer(); scheduleMedia(draft, advance, 140); return; }
    draft.status = 'checking';
    updateMediaTransfer();
    scheduleMedia(draft, () => {
      if (outcome === 'invalid') { mediaError('文件校验失败，请选择符合格式的文件。', 'invalid'); return; }
      if (outcome === 'revoked' || messageAccess(draft)) { mediaError('发送前资格已失效，附件没有发送。', 'revoked'); return; }
      if (Date.now() - draft.createdAt >= 15 * 60 * 1000) { mediaError('草稿已过期，请重新选择文件。', 'expired'); return; }
      draft.status = 'sending'; updateMediaTransfer();
      scheduleMedia(draft, () => {
        const list = messageList(draft);
        if (messageAccess(draft) || !list) { mediaError('资格已失效，附件没有发送。', 'revoked'); return; }
        if (groupMediaBytes(draft.groupId) + media.size > MEDIA_QUOTA) { mediaError('此群附件空间已满，请清理后重试。'); return; }
        if (state.service === 'offline' || draft.scope === 'room' && state.network !== 'connected') { mediaError('连接已中断，附件未完成确认，请重试。'); return; }
        const delivery = deliveryState(draft);
        list.push({ id: 'msg-' + state.nextId++, user: me, time: timeNow(), text: draft.caption, media, delivery });
        const scope = draft.scope;
        state.pendingMedia = null;
        closeModal(); render(); scrollChatToBottom(scope);
        toast(delivery === 'pending' ? '本机保存 · 待同步（模拟）；暂无可达接收端。' : delivery === 'partial' ? '部分送达（模拟）；其他成员待同步。' : '对端已接收（模拟，未真实落盘）。');
      }, 140);
    }, 240);
  };
  scheduleMedia(draft, advance, 140);
}

function sendTextMessage(scope) {
  const context = messageContext(scope), input = document.getElementById(scope + '-message-input');
  const error = messageAccess(context);
  if (error || state.service === 'offline') { toast(error || '服务不可达，草稿已保留。'); return; }
  if (scope === 'room' && state.network !== 'connected') { toast('虚拟网络未就绪，草稿已保留。'); return; }
  const text = input.value.trim();
  if (!text) return;
  if ([...text].length > 4000) { toast('消息不可超过 4000 个字符。'); return; }
  messageList(context).push({ id: 'msg-' + state.nextId++, user: me, time: timeNow(), text, delivery: deliveryState(context) });
  state.drafts[scope][context.id] = '';
  render(); scrollChatToBottom(scope);
}
