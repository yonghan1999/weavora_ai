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
  document.getElementById('network-reason').textContent = '语音保持已连接';
  await new Promise(resolve => setTimeout(resolve,900));
  document.getElementById('network-state').textContent = '虚拟网络已连接（模拟）';
  document.getElementById('network-reason').textContent = '真实系统须收到网络服务确认，才能进入成功状态';
  button.disabled = false;
  button.removeAttribute('aria-busy');
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
