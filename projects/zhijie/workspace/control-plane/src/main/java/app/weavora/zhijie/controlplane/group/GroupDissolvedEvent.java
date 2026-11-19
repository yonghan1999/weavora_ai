package app.weavora.zhijie.controlplane.group;

/** 同步参与解散事务，全部清理成功后才向调用方确认。 */
public record GroupDissolvedEvent(String groupId) {
}
