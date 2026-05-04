为Solon AI提供一个AG-UI协议的事件包，使得用户在开发Agent时，能快速引入对应的事件，这样的好处是，避免了用户手动构造事件的复杂性。

注意：由于原仓库最低版本要求Java 17，为了尽量与官方协议拉齐，同时方便接入，本包中的主要代码均直接来自于官方[Github](https://github.com/ag-ui-protocol/ag-ui/tree/main/sdks/community/java)仓库，在修改兼容JDK8基础上，定时与上游保持同步。

先了解：https://docs.ag-ui.com/concepts/events
然后在agent的stream回调中向前端返回必要的事件