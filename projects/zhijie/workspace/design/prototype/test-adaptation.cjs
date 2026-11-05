module.exports = async ({evaluate, click, assert, delay, command, screenshot}) => {
  const reset=async()=>{await command('Page.reload',{ignoreCache:true});await delay(300);};
  const scenario=async name=>{await click('review-scenarios');await evaluate(`document.querySelector('[data-scenario="${name}"]').click()`);};
  await reset();await evaluate('enterRoom("weekend")');await delay(800);
  await evaluate('state.view="settings";state.settingsTab="voice";render()');
  assert('CA-03 降噪默认开启并明确模拟',await evaluate('state.settings.noise&&document.querySelector("[data-setting=noise]").getAttribute("aria-pressed")==="true"&&document.body.innerText.includes("降噪已开启（模拟）")'));
  await evaluate('document.querySelector("[data-setting=noise]").click()');
  assert('CA-03 降噪可关闭且不停止连接',await evaluate('!state.settings.noise&&state.voice==="connected"'));
  await click('toggle-mute');await evaluate('document.querySelector("[data-setting=noise]").click()');
  assert('CA-03 静音优先于降噪',await evaluate('state.muted&&!state.speaking&&document.body.innerText.includes("麦克风已静音")'));
  await scenario('noise-fail');
  assert('CA-04 处理器受限不假装开启或关闭整条语音',await evaluate('state.voice==="connected"&&document.querySelector("[data-setting=noise]").disabled&&document.querySelector("[data-setting=noise]").getAttribute("aria-pressed")==="false"&&document.body.innerText.includes("降噪暂不可用")'));
  await scenario('audio-reset');
  assert('CA-05 设备测试初始空闲且无真实采集',await evaluate('state.deviceTest==="idle"&&document.body.innerText.includes("模拟测试，不采集")'));
  await click('device-test');assert('CA-05 测试中可停止',await evaluate('state.deviceTest==="running"&&document.querySelector("[data-action=device-test]").textContent==="停止测试"'));
  await click('device-test');await delay(1600);
  assert('CA-05 停止后迟到完成被取消',await evaluate('state.deviceTest==="stopped"'));
  await click('device-test');await delay(1600);assert('CA-05 模拟成功状态',await evaluate('state.deviceTest==="success"'));
  await scenario('test-fail');await click('device-test');await delay(1600);assert('CA-05 模拟失败状态',await evaluate('state.deviceTest==="failed"'));
  await scenario('audio-permission');
  assert('CA-04 权限拒绝区分于处理失败且网络保持',await evaluate('state.voice==="failed"&&state.network==="connected"&&document.body.innerText.includes("无法使用麦克风")'));
  await scenario('audio-reset');await evaluate('retryChannel("voice")');await delay(600);await scenario('voice-device');
  assert('CA-04 设备拔出显示独立原因',await evaluate('state.voice==="failed"&&state.network==="connected"&&document.body.innerText.includes("设备已断开")'));
  await scenario('audio-reset');await evaluate('retryChannel("voice")');await delay(600);
  await scenario('voice-capacity');await delay(600);
  assert('CA-02 语音容量拒绝结束忙碌状态且网络保持',await evaluate('state.voice==="failed"&&state.network==="connected"&&state.channelErrors.voice.includes("暂时无法连接")&&state.sessionStatus==="active"'));
  await evaluate('retryChannel("voice")');await delay(600);await scenario('network-capacity');await delay(600);
  assert('CA-02 网络容量拒绝保持语音并隐藏可用地址',await evaluate('state.network==="failed"&&state.voice==="connected"&&!document.querySelector(".member-ip-list")'));
  await scenario('direct-only');await scenario('direct-fail');await delay(600);
  assert('CA-06 禁止中继时直连失败不能偷偷回退',await evaluate('state.network==="failed"&&state.paths.network===null&&state.policy==="direct-only"'));
  await scenario('direct-first');await delay(600);
  assert('CA-07 允许时自动回退且普通页面不显示线路开关',await evaluate('state.network==="connected"&&state.paths.network==="relay"&&!document.querySelector("#app [data-scenario],#app [data-change=policy]")&&!document.getElementById("app").innerText.includes("中继")'));
  await scenario('route-reset');await scenario('relay-only');await delay(600);
  assert('CA-07 强制中继不得选择可用直连',await evaluate('state.network==="connected"&&state.paths.network==="relay"&&state.directAvailable'));
  await scenario('relay-fail');await delay(600);
  assert('CA-08 策略迁移失败如实展示',await evaluate('state.network==="failed"&&state.channelErrors.network.includes("当前网络")&&state.paths.network===null'));
  await scenario('route-reset');await scenario('direct-first');await scenario('direct-only');
  assert('CA-08 策略变化先显示重连',await evaluate('state.network==="retrying"'));
  await delay(600);assert('CA-08 旧策略异步结果不能覆盖新策略',await evaluate('state.network==="connected"&&state.policy==="direct-only"&&state.paths.network==="direct"'));
  await evaluate('disconnectActivity();window.savedIp=myMember(currentRoom()).ip;state.settings.recover=false;render()');await scenario('recover');
  assert('CA-09 关闭自动恢复仍保留名额和手动恢复入口',await evaluate('!!state.recovery&&state.network==="disconnected"&&myMember(currentRoom()).ip===window.savedIp&&!!document.querySelector("[data-action=network-retry]")'));
  await click('network-retry');await delay(600);assert('CA-09 有效同设备活动手动恢复沿用IP',await evaluate('!state.recovery&&state.network==="connected"&&myMember(currentRoom()).ip===window.savedIp'));
  for (const [name, mutation] of [['设备变化','state.activeDevice="另一台电脑"'],['账号变化','state.recovery.account="其他账号"'],['旧活动','state.recovery.epoch--'],['超时','state.recovery.until=Date.now()-1']]) {
    await reset();await evaluate(`enterRoom("weekend");disconnectActivity();${mutation};recoverActivity()`);await delay(800);
    assert(`CA-10 ${name}禁止恢复`,await evaluate('!state.joined&&state.network==="disconnected"&&state.voice==="disconnected"'));
  }
  for (const [name, mutation] of [['失权','removeGroupMember(groups[0],me)'],['退出','leaveRoom()'],['设备转移','revokeActivity();state.activeDevice="另一台电脑"'],['活动结束','showRoomSettings("weekend");document.querySelector("[data-action=end-room]").click();document.querySelector("[data-action=confirm-end-room]").click()']]) {
    await reset();await evaluate(`enterRoom("weekend");disconnectActivity();${mutation};recoverActivity()`);await delay(800);
    assert(`CA-10 ${name}后迟到恢复不复活`,await evaluate('!state.joined&&state.network==="disconnected"&&state.voice==="disconnected"'));
  }
  await reset();await evaluate('enterRoom("weekend");state.roomTab="chat";render();document.getElementById("room-message-input").value="共享群文字";document.querySelector("[data-form=room-message]").requestSubmit()');
  assert('CA-11 房间入口标明群与受众且没有独立消息库',await evaluate('document.querySelector(".chat-scroll").innerText.includes("所有有效群成员可见")&&groups[0].messages.at(-1).text==="共享群文字"&&!Object.hasOwn(currentRoom(),"messages")'));
  await evaluate('state.groupId="expedition";state.view="groupChat";render()');
  assert('CA-11 群入口同步同一消息',await evaluate('document.querySelector(".group-chat-body").innerText.includes("共享群文字")'));
  await evaluate('document.getElementById("group-message-input").value="远征草稿";document.getElementById("group-message-input").dispatchEvent(new Event("input",{bubbles:true}));enterRoom("star");state.roomTab="chat";render()');
  assert('CA-12 跨群切房隔离记录和草稿并明确目标群',await evaluate('document.getElementById("room-message-input").value===""&&document.querySelector(".chat-scroll").innerText.includes("群聊 · 星野观测站")&&!document.querySelector(".chat-scroll").innerText.includes("共享群文字")&&state.drafts.group.expedition==="远征草稿"'));
  await evaluate('document.getElementById("room-message-input").value="星野专属";document.querySelector("[data-form=room-message]").requestSubmit()');
  assert('CA-12 发送目标采用当前房间所属群而非旧导航群',await evaluate('groups.find(g=>g.id==="observatory").messages.at(-1).text==="星野专属"&&!groups[0].messages.some(m=>m.text==="星野专属")'));
  await evaluate('window.oldForm=document.querySelector("[data-form=room-message]");window.oldCount=groups.find(g=>g.id==="observatory").messages.length;removeGroupMember(groups.find(g=>g.id==="observatory"),me);render();window.oldForm.querySelector("input").value="禁止错发";document.body.append(window.oldForm);window.oldForm.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}));window.oldForm.remove()');
  assert('CA-12 失权后不展示群内容且不新增消息',await evaluate('groups.find(g=>g.id==="observatory").messages.length===window.oldCount&&!document.querySelector(".chat-scroll")'));
  await reset();await evaluate('enterRoom("weekend");state.roomTab="chat";render()');
  await evaluate('currentRoom().members=Array.from({length:11},(_,i)=>({name:"保留成员"+i,voice:i%2?"connected":"disconnected",ip:"10.66.0."+(i+30)})).concat(myMember(currentRoom()));disconnectActivity();render();window.reservationCount=currentRoom().members.length');
  assert('CA-01 12人含断线保留且本人恢复不重复占额',await evaluate('window.reservationCount===12&&!!state.recovery&&joinError(currentRoom(),"",false)===""'));
  await evaluate('recoverActivity()');await delay(600);
  assert('CA-01 满额房间有效恢复保持12个不同账号',await evaluate('currentRoom().members.length===12&&new Set(currentRoom().members.map(m=>m.name)).size===12&&state.network==="connected"'));
  await command('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});await screenshot('b1-adaptation-full-room.png');
  await reset();await evaluate('enterRoom("weekend");state.roomTab="chat";render()');
  for(const width of [1440,900,600]) {
    await command('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:false});
    assert(`CA-11 ${width}px群消息发送入口可见可点击且无横向溢出`,await evaluate('(()=>{const b=document.querySelector(".chat-compose button"),r=b.getBoundingClientRect();return document.documentElement.scrollWidth<=innerWidth&&r.bottom<=innerHeight&&document.querySelector(".chat-compose input").getBoundingClientRect().width>0&&b.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()'));
    await screenshot(`b1-adaptation-room-${width}.png`);
  }
  await evaluate('state.view="settings";render()');await screenshot('b1-adaptation-audio.png');
};
