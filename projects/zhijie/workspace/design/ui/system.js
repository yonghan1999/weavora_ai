document.querySelectorAll('[data-demo]').forEach(button => button.addEventListener('click',() => {
  document.getElementById('action-result').textContent = `已触发「${button.textContent}」示例；不执行产品数据操作。`;
}));

document.getElementById('pending-action').addEventListener('click',async event => {
  const button = event.currentTarget;
  button.disabled = true;
  button.setAttribute('aria-busy','true');
  button.textContent = '提交中…';
  await new Promise(resolve => setTimeout(resolve,900));
  button.disabled = false;
  button.removeAttribute('aria-busy');
  button.textContent = '模拟异步提交';
  document.getElementById('action-result').textContent = '模拟提交完成；等待期间按钮禁用，避免重复发送。';
});

document.getElementById('retry-demo').addEventListener('click',async event => {
  const button = event.currentTarget;
  button.disabled = true;
  button.setAttribute('aria-busy','true');
  document.getElementById('network-state').textContent = '虚拟网络重试中';
  document.getElementById('voice-state').textContent = '语音等待虚拟网络';
  document.getElementById('network-reason').textContent = '隧道未就绪，语音与内容传输等待；无服务器回退';
  await new Promise(resolve => setTimeout(resolve,900));
  document.getElementById('network-state').textContent = '虚拟网络已连接（模拟）';
  document.getElementById('voice-state').textContent = '语音已连接（模拟）';
  document.getElementById('network-reason').textContent = '先确认隧道就绪，再连接语音；真实成功须由对应协议事件确认';
  button.disabled = false;
  button.removeAttribute('aria-busy');
});

const replicaStates = {
  offline: ['无在线可达副本 · 缺失内容暂不可用', '本机保存 · 待同步（模拟）'],
  partial: ['部分成员无法直连 · 仅向可达成员同步', '部分送达 · 其他成员待同步（模拟）'],
  online: ['客户端保存 · 在线成员间同步', '对端已接收（模拟；正式须接收端校验落盘确认）']
};
document.getElementById('replica-mode').addEventListener('change',event => {
  const [notice, receipt] = replicaStates[event.target.value];
  document.getElementById('replica-notice').textContent = notice;
  const result = document.getElementById('replica-receipt');
  result.textContent = receipt;
  result.dataset.delivery = { offline: 'pending', partial: 'partial', online: 'received' }[event.target.value];
});

const nameInput = document.getElementById('room-name-demo');
const fieldFeedback = document.getElementById('field-feedback');
document.getElementById('form-demo').addEventListener('submit',event => {
  event.preventDefault();
  const valid = !!nameInput.value.trim();
  fieldFeedback.hidden = valid;
  fieldFeedback.textContent = valid ? '' : '请填写 1–60 个字符的房间名称。';
  if (valid) {
    nameInput.removeAttribute('aria-invalid');
    nameInput.removeAttribute('aria-describedby');
  } else {
    nameInput.setAttribute('aria-invalid','true');
    nameInput.setAttribute('aria-describedby',fieldFeedback.id);
    nameInput.focus();
  }
  document.getElementById('form-result').textContent = valid ? '名称校验通过；此处不创建房间。' : '';
});
nameInput.addEventListener('input',() => {
  nameInput.removeAttribute('aria-invalid');
  nameInput.removeAttribute('aria-describedby');
  fieldFeedback.hidden = true;
  document.getElementById('form-result').textContent = '';
});
