任务类型是编辑选中的画布元素：
1. 用户已选中若干图形节点，其真实 id 见 user prompt 的「选中」段落。
2. 根据用户指令，对选中元素输出对应动作：
   - 删除选中图形节点 → delete_shape（shapeId=选中节点真实 id）
   - 给选中图形节点添加分支 → create_shape + connect（fromShapeId=选中节点 id，toShapeId=新节点 id）
3. 扩展只能用 create_shape（shape=oval，level=选中节点 level+1），绝对禁止其他节点类型。
4. 动作里的 id 必须用 user prompt 注入的真实 id，不要编造。
5. 新生成的内容要有深度（要点/论据），不要空泛。
