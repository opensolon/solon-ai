package features.ai.core;

/**
 * @author noear 2025/6/11 created
 */

import org.noear.solon.annotation.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * 案件信息业务对象 zf_case
 *
 * @author chengliang4810
 * @date 2025-02-17
 */
public class CaseBo {

    /**
     * 案件基本信息ID
     */
    @Param(description = "案件ID, 传递时使用String类型")
    private Long caseId;

    /**
     * 自由裁量情况总结
     */
    @Param(description = "自由裁量情况总结")
    private String discretionSummary;

    /**
     * 告知听证总结
     */
    @Param(description = "告知听证总结")
    private String noticeHearing;

    /**
     * 解除行政强制措施依据
     */
    @Param(description = "解除行政强制措施依据")
    private String uncComMeaBasis;

    /**
     * 案件使用的模板id
     */
    @Param(description = "案件使用的模板id")
    private Long caseTemplateId;

    /**
     * 案件来源及调查经过
     */
    @Param(description = "案件来源及调查经过")
    private String sourceAndInvestigation;
    /**
     * 案件的拓展数据
     */
    @Param(description = "案件的拓展数据")
    private Map<String, Object> ext;
    /**
     * 行政处罚告知时间
     */
    @Param(description = "行政处罚告知时间")
    private Date penaltyNoticeTime;
    /**
     * 是否陈述申辩
     */
    @Param(description = "是否陈述申辩")
    private Boolean needDefense;
    /**
     * 陈述申辩记录人
     */
    @Param(description = "陈述申辩记录人")
    private String defenseRecorder;
    /**
     * 陈述申辩人
     */
    @Param(description = "陈述申辩人")
    private String defensePerson;
    /**
     * 陈述申辩时间
     */
    @Param(description = "陈述申辩时间")
    private Date defenseTime;
    /**
     * 法治审核送审时间
     */
    @Param(description = "法治审核送审时间")
    private Date lawAuditApplyTime;
    /**
     * 法治审核退卷时间
     */
    @Param(description = "法治审核退卷时间")
    private Date lawAuditBackTime;
    /**
     * 证据材料文档
     */
    @Param(description = "证据材料文档")
    private String evidenceDocument;
    /**
     * 陈述申辩地点
     */
    @Param(description = "陈述申辩地点")
    private String defensePlace;
    /**
     * 陈述申辩请求
     */
    @Param(description = "陈述申辩请求")
    private String defenseRequest;
    /**
     * 陈述申辩内容
     */
    @Param(description = "陈述申辩内容")
    private String defenseContent;
    /**
     * 行政处罚复核意见
     */
    @Param(description = "行政处罚复核意见")
    private String penaltyReviewOpinion;
    /**
     * 案件来源
     */
    @Param(description = "案件来源")
    private String caseSo;
    /**
     * 案源内容
     */
    @Param(description = "案源内容")
    private String caseCon;
    /**
     * 发现线索/收到材料时间
     */
    @Param(description = "发现线索/收到材料时间")
    private Date clueTime;
    /**
     * 执法业务领域
     */
    @Param(description = "执法业务领域")
    private String caseArea;
    /**
     * 业务领域 对应“执法环节” 执法部门统一叫法
     * 值为选项:  餐饮 经营 使用 生产 无
     */
    @Param(description = "业务领域 对应“执法环节” 执法部门统一叫法")
    private String businessDomain;
    /**
     * 案件编号
     */
    @Param(description = "案件编号")
    private String caseNo;
    /**
     * 案件名称
     */
    @Param(description = "案件名称")
    private String caseName;
    /**
     * 案由
     */
    @Param(description = "案由")
    private String caseReason;
    /**
     * 已立案
     */
    @Param(description = "已立案")
    private Boolean caseFiled;
    /**
     * 立案机关代码
     */
    @Param(description = "立案机关代码")
    private String caseFiAuth;
    /**
     * 立案机关名称
     */
    @Param(description = "立案机关名称")
    private String caseFiAuthName;
    /**
     * 立案日期
     */
    @Param(description = "立案日期")
    private Date caseFiDate;
    /**
     * 办案机构代码
     */
    @Param(description = "办案机构代码")
    private String caseDep;
    /**
     * 办案机构名称
     */
    @Param(description = "办案机构名称")
    private String caseDepName;
    /**
     * 处理决定
     */
    @Param(description = "处理决定")
    private String penResult;
    /**
     * 结案情形
     */
    @Param(description = "结案情形")
    private String caseEndType;
    /**
     * 结案日期
     */
    @Param(description = "结案日期")
    private Date caseEndDate;
    /**
     * 数据汇总单位
     */
    @Param(description = "数据汇总单位")
    private String souExtFromNode;
    /**
     * 数据汇总时间
     */
    @Param(description = "数据汇总时间")
    private Date souExtDataTime;
    /**
     * 登记人
     */
    @Param(description = "登记人")
    private String registrant;
    /**
     * 检查类型
     */
    @Param(description = "检查类型")
    private String inspectionType;
    /**
     * 是否有违法行为
     */
    @Param(description = "是否有违法行为")
    private Boolean hasViolation;
    /**
     * 违法行为处理措施
     */
    @Param(description = "违法行为处理措施")
    private String violationMeasures;
    /**
     * 是否进行复查
     */
    @Param(description = "是否进行复查")
    private Boolean isReinspection;
    /**
     * 登记时间
     */
    @Param(description = "登记时间")
    private Date registrationTime;
    /**
     * 执法证件是否查看
     */
    @Param(description = "执法证件是否查看")
    private String credentialsReview;
    /**
     * 申请检查人员回避情况
     */
    @Param(description = "申请检查人员回避情况")
    private String recusalRequest;
    /**
     * 通知当事人到场情况
     */
    @Param(description = "通知当事人到场情况")
    private String partyNotificationStatus;
    /**
     * 陈述申辩内容
     */
    @Param(description = "陈述申辩内容")
    private String statementDefenseContent;
    /**
     * 当事人依法享有的权利、救济途径情况
     */
    @Param(description = "当事人依法享有的权利、救济途径情况")
    private String legalRightsRemedies;
    /**
     * 当事人笔录意见
     */
    @Param(description = "当事人笔录意见")
    private String partyOpinion;
    /**
     * 监督检查人姓名
     */
    @Param(description = "监督检查人姓名")
    private String checkName;
    /**
     * 所属单位
     */
    @Param(description = "所属单位")
    private String affiliatedUnit;
    /**
     * 单位名称
     */
    @Param(description = "单位名称")
    private String reportUnitName;
    /**
     * 监督检查人2姓名
     */
    @Param(description = "监督检查人2姓名")
    private String checkName2;
    /**
     *  监督检查人2所属单位
     */
    @Param(description = "监督检查人2所属单位")
    private String affiliatedUnit2;
    /**
     *  监督检查人2单位名称
     */
    @Param(description = "监督检查人2单位名称")
    private String reportUnitName2;
    /**
     * 法定代表人（负责人）
     */
    @Param(description = "法定代表人（负责人）")
    private String legalRepresentative;
    /**
     * 处罚方式
     */
    @Param(description = "处罚方式")
    private String penaltyType;
    /**
     * 罚款缴纳方式
     */
    @Param(description = "罚款缴纳方式")
    private String serveType;
    /**
     * 现场笔录开始时间
     */
    @Param(description = "现场笔录开始时间")
    private Date siteRecordStartTime;

    /**
     * 现场笔录结束时间
     */
    @Param(description = "现场笔录结束时间")
    private Date siteRecordEndTime;
    /**
     * 投诉人、举报人类型
     */
    @Param(description = "投诉人、举报人类型")
    private String complainantType;
    /**
     * 投诉人、举报人姓名
     */
    @Param(description = "投诉人、举报人姓名")
    private String reportName;
    /**
     * 身份证件号码
     */
    @Param(description = "身份证件号码")
    private String idCardNumber;
    /**
     * 联系电话
     */
    @Param(description = "联系电话")
    private String contactPhone;
    /**
     * 其他联系方式
     */
    @Param(description = "其他联系方式")
    private String otherContactInfo;
    /**
     * 联系地址
     */
    @Param(description = "联系地址")
    private String contactAddress;
    /**
     * 移送、交办部门名称
     */
    @Param(description = "移送、交办部门名称")
    private String assignUnitName;
    /**
     * 联系人
     */
    @Param(description = "联系人")
    private String assignsContacts;
    /**
     * 联系电话
     */
    @Param(description = "联系电话")
    private String assignsPhone;
    /**
     * 联系地址
     */
    @Param(description = "联系地址")
    private String assignsAddress;
    /**
     * 移送原因
     */
    @Param(description = "移送原因")
    private String transferReason;
    /**
     * 移送日期
     */
    @Param(description = "移送日期")
    private Date transferDate;
    /**
     * 交办日期
     */
    @Param(description = "交办日期")
    private Date assignDate;
    /**
     * 适用程序
     */
    @Param(description = "适用程序")
    private String applicableProcedure;

    /**
     * 核查情况，现场检查时的客观情况记录
     */
    @Param(description = "核查情况，现场检查时的客观情况记录")
    private String inspectionDetails;
    /**
     * 依据说明（立案/不予立案 理由）
     */
    @Param(description = "依据说明（立案/不予立案 理由）")
    private String dispositionReason;
    /**
     * 不予立案日期
     */
    @Param(description = "不予立案日期")
    private Date nonCaseFilingDate;
    /**
     * 检查起始时间
     */
    @Param(description = "检查起始时间")
    private Date inspectionStartTime;
    /**
     * 检查截止时间
     */
    @Param(description = "检查截止时间")
    private Date inspectionEndTime;
    /**
     * 检查地点
     */
    @Param(description = "检查地点")
    private String insSpot;
    /**
     * 检查人
     */
    @Param(description = "检查人")
    private String inspectors;

    /**
     * 执法人员Id
     */
    @Param(description = "执法人员Id")
    private Long inspectorId;

    /**
     * 见证人
     */
    @Param(description = "见证人")
    private String witness;
    /**
     * 见证人单位
     */
    @Param(description = "见证人单位")
    private String witnessWorkUnit;
    /**
     * 见证人职务
     */
    @Param(description = "见证人职务")
    private String witnessPosition;
    /**
     * 检查项目
     */
    @Param(description = "检查项目")
    private String inspectionItem;
    /**
     * 现场情况
     */
    @Param(description = "现场情况")
    private String siteCondition;
    /**
     * 协查原因
     */
    @Param(description = "协查原因")
    private String assistanceReason;
    /**
     * 协助调查事项
     */
    @Param(description = "协助调查事项")
    private String assistanceMatters;
    /**
     * 受委托单位
     */
    @Param(description = "受委托单位")
    private String entrustedUnitName;
    /**
     * 委托类型
     */
    @Param(description = "委托类型")
    private String commissionType;
    /**
     * 委托事项
     */
    @Param(description = "委托事项")
    private String commissionMatters;
    /**
     * 委托日期
     */
    @Param(description = "委托日期")
    private Date commissionDate;
    /**
     * 文书编号
     */
    @Param(description = "文书编号")
    private String documentNumber;
    /**
     * 行政强制措施类型
     */
    @Param(description = "行政强制措施类型")
    private String comMeaType;
    /**
     * 行政强制措施决定日期
     */
    @Param(description = "行政强制措施决定日期")
    private Date comMeaDecDate;
    /**
     * 解除行政强制措施决定日期
     */
    @Param(description = "解除行政强制措施决定日期")
    private Date uncComMeaDecDate;
    /**
     * 行政强制措施解除日期，市场监管机关解除对当事人的强制措施的日期
     */
    @Param(description = "行政强制措施解除日期，市场监管机关解除对当事人的强制措施的日期")
    private Date uncDate;
    /**
     * 行政强制措施期限
     */
    @Param(description = "行政强制措施期限")
    private Integer comMeaDuration;
    /**
     * 存放地点
     */
    @Param(description = "存放地点")
    private String storageLocation;
    /**
     * 通知日期
     */
    @Param(description = "通知日期")
    private Date noticeDate;
    /**
     * 是否提出陈述申辩
     */
    @Param(description = "是否提出陈述申辩")
    private Boolean statementDefense;
    /**
     * 是否要求听证
     */
    @Param(description = "是否要求听证")
    private Boolean hearing;
    /**
     * 听证起始时间
     */
    @Param(description = "听证起始时间")
    private Date hearingStartTime;
    /**
     * 听证截止时间
     */
    @Param(description = "听证截止时间")
    private Date hearingEndTime;
    /**
     * 听证地点
     */
    @Param(description = "听证地点")
    private String hearingLocation;
    /**
     * 听证主持人
     */
    @Param(description = "听证主持人")
    private String hearingChairman;
    /**
     * 记录员
     */
    @Param(description = "记录员")
    private String recorder;
    /**
     * 翻译人员
     */
    @Param(description = "翻译人员")
    private String translator;
    /**
     * 听证基本情况
     */
    @Param(description = "听证基本情况")
    private String hearingBasicSituation;
    /**
     * 处理意见及建议
     */
    @Param(description = "处理意见及建议")
    private String handlingOpinionAndSuggestions;
    /**
     * 行为
     */
    @Param(description = "行为")
    private String behavior;
    /**
     * 违反规定
     */
    @Param(description = "违反规定")
    private String violationOfRegulations;
    /**
     * 限期改正日期
     */
    @Param(description = "限期改正日期")
    private Date deadlineToCorrect;
    /**
     * 改正内容
     */
    @Param(description = "改正内容")
    private String correctionContent;
    /**
     * 改正通知日期
     */
    @Param(description = "改正通知日期")
    private Date correctionNoticeDate;
    /**
     * 物品来源
     */
    @Param(description = "物品来源")
    private String itemSource;
    /**
     * 处理日期
     */
    @Param(description = "处理日期")
    private Date processingDate;
    /**
     * 处理地点
     */
    @Param(description = "处理地点")
    private String processingLocation;
    /**
     * 执行人员
     */
    @Param(description = "执行人员")
    private String executingPerson;
    /**
     * 监督人
     */
    @Param(description = "监督人")
    private String supervisor;
    /**
     * 物品处理情况
     */
    @Param(description = "物品处理情况")
    private String itemHandling;
    /**
     * 复议请求
     */
    @Param(description = "复议请求")
    private String reviewRequest;
    /**
     * 申请复议事项
     */
    @Param(description = "申请复议事项")
    private String applicationReviewItem;
    /**
     * 申请日期
     */
    @Param(description = "申请日期")
    private Date applicationDate;
    /**
     * 受理日期
     */
    @Param(description = "受理日期")
    private Date acceptingDate;
    /**
     * 复议机关
     */
    @Param(description = "复议机关")
    private String reviewAuthority;
    /**
     * 复议决定日期
     */
    @Param(description = "复议决定日期")
    private Date reviewDecisionDate;
    /**
     * 复议决定类型
     */
    @Param(description = "复议决定类型")
    private String reviewDecisionType;
    /**
     * 复议决定结果
     */
    @Param(description = "复议决定结果")
    private String reviewDecisionResult;
    /**
     * 立卷人
     */
    @Param(description = "立卷人")
    private String filingPerson;
    /**
     * 归档日期
     */
    @Param(description = "归档日期")
    private Date archivingDate;
    /**
     * 归档号
     */
    @Param(description = "归档号")
    private String archivingNumber;
    /**
     * 保管期限
     */
    @Param(description = "保管期限")
    private Long retentionPeriod;
    /**
     * 处罚决定书文号
     */
    @Param(description = "处罚决定书文号")
    private String penaltyDecisionNumber;
    /**
     * 执行方式
     */
    @Param(description = "执行方式")
    private String executionMethod;
    /**
     * 结案日期
     */
    @Param(description = "结案日期")
    private Date caseClosureDate;
    /**
     * 执行日期
     */
    @Param(description = "执行日期")
    private Date executionDate;
    /**
     * 执行金额
     */
    @Param(description = "执行金额")
    private Long executionAmount;
    /**
     * 当事人类型
     */
    @Param(description = "当事人类型")
    private String partyType;
    /**
     * 当事人名称
     */
    @Param(description = "当事人名称")
    private String unitName;
    /**
     * 当事人主体身份代码
     */
    @Param(description = "当事人主体身份代码")
    private String priPID;
    /**
     * 当事人统一社会信用代码
     */
    @Param(description = "当事人统一社会信用代码")
    private String uniSCID;
    /**
     * 当事人联系地址
     */
    @Param(description = "当事人联系地址")
    private String addr;
    /**
     * 当事人住所
     */
    @Param(description = "当事人住所")
    private String dom;
    /**
     * 当事人联系电话
     */
    @Param(description = "当事人联系电话")
    private String unitTel;
    /**
     * 当事人证件类型代码
     */
    @Param(description = "当事人证件类型代码")
    private String cerType;
    /**
     * 当事人证件号码
     */
    @Param(description = "当事人证件号码")
    private String cerNo;
    /**
     * 当事人其他联系方式
     */
    @Param(description = "当事人其他联系方式")
    private String partyOtherContactInfo;

    /**
     * 当事人法定代表人名称
     */
    @Param(description = "当事人法定代表人名称")
    private String leRep;
    /**
     * 当事人法定代表人证件类型
     */
    @Param(description = "当事人法定代表人证件类型")
    private String leRepCerType;
    /**
     * 当事人法定代表人证件号码
     */
    @Param(description = "当事人法定代表人证件号码")
    private String leRepCerNO;
    /**
     * 当事人法定代表人联系地址
     */
    @Param(description = "当事人法定代表人联系地址")
    private String leRepAddr;
    /**
     * 当事人法定代表人联系电话
     */
    @Param(description = "当事人法定代表人联系电话")
    private String leRepPhone;
    /**
     * 文书送达地址
     */
    @Param(description = "文书送达地址")
    private String documentDeliveryAddress;
    /**
     * 电子证据
     */
    @Param(description = "电子证据")
    private String electronicEvidence;
    /**
     * 法人职务
     */
    @Param(description = "法人职务")
    private String legalCompanyPosition;
    /**
     * 当事人态度
     */
    @Param(description = "当事人态度")
    private String partyAttitude;
    /**
     * 是否采取强制措施
     */
    @Param(description = "是否采取强制措施")
    private Boolean needComMea;
    /**
     * 行政强制措施依据
     */
    @Param(description = "行政强制措施依据")
    private String comMeaBasis;
    /**
     * 行政强制措施理由
     */
    @Param(description = "行政强制措施理由")
    private String comMeaReason;

    /**
     * 案件事实
     */
    @Param(description = "案件事实")
    private String illegFact;

    /**
     * 违法行为种类代码（多选，/分隔）
     */
    @Param(description = "违法行为种类代码（多选，/分隔）")
    private String illegActType;

    /**
     * 违法行为种类中文名称
     */
    @Param(description = "违法行为种类中文名称")
    private String illegActTypeName;

    /**
     * 处罚依据
     */
    @Param(description = "处罚依据")
    private String penBasisName;

    /**
     * 自由裁量的事实和理由
     */
    @Param(description = "自由裁量的事实和理由")
    private String discretionFact;

    /**
     * 是否执行包容审慎
     */
    @Param(description = "是否执行包容审慎")
    private Boolean inclusivePrudent;

    /**
     * 是否重大案件
     */
    @Param(description = "是否重大案件")
    private Boolean majorCase;
    /**
     * 是否网络交易案件
     */
    @Param(description = "是否网络交易案件")
    private Boolean networkTrade;
    /**
     * 是否为突发事件
     */
    @Param(description = "是否为突发事件")
    private Boolean emergency;

    /**
     * 是否自由裁量
     */
    @Param(description = "是否自由裁量")
    private Boolean discretion;

    /**
     * 自由裁量票据编号
     */
    @Param(description = "自由裁量票据编号")
    private String ticketNumber;

    /**
     * 自由裁量告知时间
     */
    @Param(description = "自由裁量告知时间")
    private Date discretionInformTime;

    /**
     * 自由裁量方式
     */
    @Param(description = "自由裁量方式")
    private String discretionMethod;

    /**
     * 自由裁量依据
     */
    @Param(description = "自由裁量依据")
    private String discretionBasis;

    /**
     * 自由裁量减轻处罚依据
     */
    @Param(description = "自由裁量减轻处罚依据")
    private String mitigationBasis;

    /**
     * 自由裁量行政处罚裁量积分
     */
    @Param(description = "自由裁量行政处罚裁量积分")
    private Integer penaltyDiscretionPoints;

    /**
     * 自由裁量依据材料
     */
    @Param(description = "自由裁量依据材料")
    private String discretionMaterials;

    /**
     * 行政处罚内容
     */
    @Param(description = "行政处罚内容")
    private String penContent;

    /**
     * 处理决定代码（多选，/分隔）
     */
    @Param(description = "处理决定代码（多选，/分隔）")
    private String penalDec;

    /**
     * 处理决定日期
     */
    @Param(description = "处理决定日期")
    private Date penalDecIssDate;

    /**
     * 处理决定理由
     */
    @Param(description = "处理决定理由")
    private String penalDecIssRea;

    /**
     * 处罚种类代码（多选，/分隔）
     */
    @Param(description = "处罚种类代码（多选，/分隔）")
    private String penType;

    /**
     * 案值（万元）
     */
    @Param(description = "案值（万元）")
    private BigDecimal caseVal;

    /**
     * 罚款金额（万元）
     */
    @Param(description = "罚款金额（万元）")
    private BigDecimal penAm;

    /**
     * 责令退款金额（万元）
     */
    @Param(description = "责令退款金额（万元）")
    private BigDecimal ordRefAm;

    /**
     * 没收金额（万元）
     */
    @Param(description = "没收金额（万元）")
    private BigDecimal forfAm;

    /**
     * 没收物资描述
     */
    @Param(description = "没收物资描述")
    private String forfGoods;

    /**
     * 处罚备注
     */
    @Param(description = "处罚备注")
    private String penalRemark;

    /**
     * 法治审核状态
     */
    @Param(description = "法治审核状态")
    private String lawAuditStatus;

    /**
     * 延长行政强制措施决定日期
     */
    @Param(description = "延长行政强制措施决定日期")
    private Date extComMeaDecDate;
    /**
     * 延长日期(天数)
     */
    @Param(description = "延长日期(天数)")
    private Integer extDuration;
    /**
     * 延长依据
     */
    @Param(description = "延长依据")
    private String extComMeaBasis;
    /**
     * 主体资格证照名称
     */
    @Param(description = "主体资格证照名称")
    private String qualification;


    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public String getDiscretionSummary() {
        return discretionSummary;
    }

    public void setDiscretionSummary(String discretionSummary) {
        this.discretionSummary = discretionSummary;
    }

    public String getNoticeHearing() {
        return noticeHearing;
    }

    public void setNoticeHearing(String noticeHearing) {
        this.noticeHearing = noticeHearing;
    }

    public String getUncComMeaBasis() {
        return uncComMeaBasis;
    }

    public void setUncComMeaBasis(String uncComMeaBasis) {
        this.uncComMeaBasis = uncComMeaBasis;
    }

    public Long getCaseTemplateId() {
        return caseTemplateId;
    }

    public void setCaseTemplateId(Long caseTemplateId) {
        this.caseTemplateId = caseTemplateId;
    }

    public String getSourceAndInvestigation() {
        return sourceAndInvestigation;
    }

    public void setSourceAndInvestigation(String sourceAndInvestigation) {
        this.sourceAndInvestigation = sourceAndInvestigation;
    }

    public Map<String, Object> getExt() {
        return ext;
    }

    public void setExt(Map<String, Object> ext) {
        this.ext = ext;
    }

    public Date getPenaltyNoticeTime() {
        return penaltyNoticeTime;
    }

    public void setPenaltyNoticeTime(Date penaltyNoticeTime) {
        this.penaltyNoticeTime = penaltyNoticeTime;
    }

    public Boolean getNeedDefense() {
        return needDefense;
    }

    public void setNeedDefense(Boolean needDefense) {
        this.needDefense = needDefense;
    }

    public String getDefenseRecorder() {
        return defenseRecorder;
    }

    public void setDefenseRecorder(String defenseRecorder) {
        this.defenseRecorder = defenseRecorder;
    }

    public String getDefensePerson() {
        return defensePerson;
    }

    public void setDefensePerson(String defensePerson) {
        this.defensePerson = defensePerson;
    }

    public Date getDefenseTime() {
        return defenseTime;
    }

    public void setDefenseTime(Date defenseTime) {
        this.defenseTime = defenseTime;
    }

    public Date getLawAuditApplyTime() {
        return lawAuditApplyTime;
    }

    public void setLawAuditApplyTime(Date lawAuditApplyTime) {
        this.lawAuditApplyTime = lawAuditApplyTime;
    }

    public Date getLawAuditBackTime() {
        return lawAuditBackTime;
    }

    public void setLawAuditBackTime(Date lawAuditBackTime) {
        this.lawAuditBackTime = lawAuditBackTime;
    }

    public String getEvidenceDocument() {
        return evidenceDocument;
    }

    public void setEvidenceDocument(String evidenceDocument) {
        this.evidenceDocument = evidenceDocument;
    }

    public String getDefensePlace() {
        return defensePlace;
    }

    public void setDefensePlace(String defensePlace) {
        this.defensePlace = defensePlace;
    }

    public String getDefenseRequest() {
        return defenseRequest;
    }

    public void setDefenseRequest(String defenseRequest) {
        this.defenseRequest = defenseRequest;
    }

    public String getDefenseContent() {
        return defenseContent;
    }

    public void setDefenseContent(String defenseContent) {
        this.defenseContent = defenseContent;
    }

    public String getPenaltyReviewOpinion() {
        return penaltyReviewOpinion;
    }

    public void setPenaltyReviewOpinion(String penaltyReviewOpinion) {
        this.penaltyReviewOpinion = penaltyReviewOpinion;
    }

    public String getCaseSo() {
        return caseSo;
    }

    public void setCaseSo(String caseSo) {
        this.caseSo = caseSo;
    }

    public String getCaseCon() {
        return caseCon;
    }

    public void setCaseCon(String caseCon) {
        this.caseCon = caseCon;
    }

    public Date getClueTime() {
        return clueTime;
    }

    public void setClueTime(Date clueTime) {
        this.clueTime = clueTime;
    }

    public String getCaseArea() {
        return caseArea;
    }

    public void setCaseArea(String caseArea) {
        this.caseArea = caseArea;
    }

    public String getBusinessDomain() {
        return businessDomain;
    }

    public void setBusinessDomain(String businessDomain) {
        this.businessDomain = businessDomain;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public void setCaseNo(String caseNo) {
        this.caseNo = caseNo;
    }

    public String getCaseName() {
        return caseName;
    }

    public void setCaseName(String caseName) {
        this.caseName = caseName;
    }

    public String getCaseReason() {
        return caseReason;
    }

    public void setCaseReason(String caseReason) {
        this.caseReason = caseReason;
    }

    public Boolean getCaseFiled() {
        return caseFiled;
    }

    public void setCaseFiled(Boolean caseFiled) {
        this.caseFiled = caseFiled;
    }

    public String getCaseFiAuth() {
        return caseFiAuth;
    }

    public void setCaseFiAuth(String caseFiAuth) {
        this.caseFiAuth = caseFiAuth;
    }

    public String getCaseFiAuthName() {
        return caseFiAuthName;
    }

    public void setCaseFiAuthName(String caseFiAuthName) {
        this.caseFiAuthName = caseFiAuthName;
    }

    public Date getCaseFiDate() {
        return caseFiDate;
    }

    public void setCaseFiDate(Date caseFiDate) {
        this.caseFiDate = caseFiDate;
    }

    public String getCaseDep() {
        return caseDep;
    }

    public void setCaseDep(String caseDep) {
        this.caseDep = caseDep;
    }

    public String getCaseDepName() {
        return caseDepName;
    }

    public void setCaseDepName(String caseDepName) {
        this.caseDepName = caseDepName;
    }

    public String getPenResult() {
        return penResult;
    }

    public void setPenResult(String penResult) {
        this.penResult = penResult;
    }

    public String getCaseEndType() {
        return caseEndType;
    }

    public void setCaseEndType(String caseEndType) {
        this.caseEndType = caseEndType;
    }

    public Date getCaseEndDate() {
        return caseEndDate;
    }

    public void setCaseEndDate(Date caseEndDate) {
        this.caseEndDate = caseEndDate;
    }

    public String getSouExtFromNode() {
        return souExtFromNode;
    }

    public void setSouExtFromNode(String souExtFromNode) {
        this.souExtFromNode = souExtFromNode;
    }

    public Date getSouExtDataTime() {
        return souExtDataTime;
    }

    public void setSouExtDataTime(Date souExtDataTime) {
        this.souExtDataTime = souExtDataTime;
    }

    public String getRegistrant() {
        return registrant;
    }

    public void setRegistrant(String registrant) {
        this.registrant = registrant;
    }

    public String getInspectionType() {
        return inspectionType;
    }

    public void setInspectionType(String inspectionType) {
        this.inspectionType = inspectionType;
    }

    public Boolean getHasViolation() {
        return hasViolation;
    }

    public void setHasViolation(Boolean hasViolation) {
        this.hasViolation = hasViolation;
    }

    public String getViolationMeasures() {
        return violationMeasures;
    }

    public void setViolationMeasures(String violationMeasures) {
        this.violationMeasures = violationMeasures;
    }

    public Boolean getReinspection() {
        return isReinspection;
    }

    public void setReinspection(Boolean reinspection) {
        isReinspection = reinspection;
    }

    public Date getRegistrationTime() {
        return registrationTime;
    }

    public void setRegistrationTime(Date registrationTime) {
        this.registrationTime = registrationTime;
    }

    public String getCredentialsReview() {
        return credentialsReview;
    }

    public void setCredentialsReview(String credentialsReview) {
        this.credentialsReview = credentialsReview;
    }

    public String getRecusalRequest() {
        return recusalRequest;
    }

    public void setRecusalRequest(String recusalRequest) {
        this.recusalRequest = recusalRequest;
    }

    public String getPartyNotificationStatus() {
        return partyNotificationStatus;
    }

    public void setPartyNotificationStatus(String partyNotificationStatus) {
        this.partyNotificationStatus = partyNotificationStatus;
    }

    public String getStatementDefenseContent() {
        return statementDefenseContent;
    }

    public void setStatementDefenseContent(String statementDefenseContent) {
        this.statementDefenseContent = statementDefenseContent;
    }

    public String getLegalRightsRemedies() {
        return legalRightsRemedies;
    }

    public void setLegalRightsRemedies(String legalRightsRemedies) {
        this.legalRightsRemedies = legalRightsRemedies;
    }

    public String getPartyOpinion() {
        return partyOpinion;
    }

    public void setPartyOpinion(String partyOpinion) {
        this.partyOpinion = partyOpinion;
    }

    public String getCheckName() {
        return checkName;
    }

    public void setCheckName(String checkName) {
        this.checkName = checkName;
    }

    public String getAffiliatedUnit() {
        return affiliatedUnit;
    }

    public void setAffiliatedUnit(String affiliatedUnit) {
        this.affiliatedUnit = affiliatedUnit;
    }

    public String getReportUnitName() {
        return reportUnitName;
    }

    public void setReportUnitName(String reportUnitName) {
        this.reportUnitName = reportUnitName;
    }

    public String getCheckName2() {
        return checkName2;
    }

    public void setCheckName2(String checkName2) {
        this.checkName2 = checkName2;
    }

    public String getAffiliatedUnit2() {
        return affiliatedUnit2;
    }

    public void setAffiliatedUnit2(String affiliatedUnit2) {
        this.affiliatedUnit2 = affiliatedUnit2;
    }

    public String getReportUnitName2() {
        return reportUnitName2;
    }

    public void setReportUnitName2(String reportUnitName2) {
        this.reportUnitName2 = reportUnitName2;
    }

    public String getLegalRepresentative() {
        return legalRepresentative;
    }

    public void setLegalRepresentative(String legalRepresentative) {
        this.legalRepresentative = legalRepresentative;
    }

    public String getPenaltyType() {
        return penaltyType;
    }

    public void setPenaltyType(String penaltyType) {
        this.penaltyType = penaltyType;
    }

    public String getServeType() {
        return serveType;
    }

    public void setServeType(String serveType) {
        this.serveType = serveType;
    }

    public Date getSiteRecordStartTime() {
        return siteRecordStartTime;
    }

    public void setSiteRecordStartTime(Date siteRecordStartTime) {
        this.siteRecordStartTime = siteRecordStartTime;
    }

    public Date getSiteRecordEndTime() {
        return siteRecordEndTime;
    }

    public void setSiteRecordEndTime(Date siteRecordEndTime) {
        this.siteRecordEndTime = siteRecordEndTime;
    }

    public String getComplainantType() {
        return complainantType;
    }

    public void setComplainantType(String complainantType) {
        this.complainantType = complainantType;
    }

    public String getReportName() {
        return reportName;
    }

    public void setReportName(String reportName) {
        this.reportName = reportName;
    }

    public String getIdCardNumber() {
        return idCardNumber;
    }

    public void setIdCardNumber(String idCardNumber) {
        this.idCardNumber = idCardNumber;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getOtherContactInfo() {
        return otherContactInfo;
    }

    public void setOtherContactInfo(String otherContactInfo) {
        this.otherContactInfo = otherContactInfo;
    }

    public String getContactAddress() {
        return contactAddress;
    }

    public void setContactAddress(String contactAddress) {
        this.contactAddress = contactAddress;
    }

    public String getAssignUnitName() {
        return assignUnitName;
    }

    public void setAssignUnitName(String assignUnitName) {
        this.assignUnitName = assignUnitName;
    }

    public String getAssignsContacts() {
        return assignsContacts;
    }

    public void setAssignsContacts(String assignsContacts) {
        this.assignsContacts = assignsContacts;
    }

    public String getAssignsPhone() {
        return assignsPhone;
    }

    public void setAssignsPhone(String assignsPhone) {
        this.assignsPhone = assignsPhone;
    }

    public String getAssignsAddress() {
        return assignsAddress;
    }

    public void setAssignsAddress(String assignsAddress) {
        this.assignsAddress = assignsAddress;
    }

    public String getTransferReason() {
        return transferReason;
    }

    public void setTransferReason(String transferReason) {
        this.transferReason = transferReason;
    }

    public Date getTransferDate() {
        return transferDate;
    }

    public void setTransferDate(Date transferDate) {
        this.transferDate = transferDate;
    }

    public Date getAssignDate() {
        return assignDate;
    }

    public void setAssignDate(Date assignDate) {
        this.assignDate = assignDate;
    }

    public String getApplicableProcedure() {
        return applicableProcedure;
    }

    public void setApplicableProcedure(String applicableProcedure) {
        this.applicableProcedure = applicableProcedure;
    }

    public String getInspectionDetails() {
        return inspectionDetails;
    }

    public void setInspectionDetails(String inspectionDetails) {
        this.inspectionDetails = inspectionDetails;
    }

    public String getDispositionReason() {
        return dispositionReason;
    }

    public void setDispositionReason(String dispositionReason) {
        this.dispositionReason = dispositionReason;
    }

    public Date getNonCaseFilingDate() {
        return nonCaseFilingDate;
    }

    public void setNonCaseFilingDate(Date nonCaseFilingDate) {
        this.nonCaseFilingDate = nonCaseFilingDate;
    }

    public Date getInspectionStartTime() {
        return inspectionStartTime;
    }

    public void setInspectionStartTime(Date inspectionStartTime) {
        this.inspectionStartTime = inspectionStartTime;
    }

    public Date getInspectionEndTime() {
        return inspectionEndTime;
    }

    public void setInspectionEndTime(Date inspectionEndTime) {
        this.inspectionEndTime = inspectionEndTime;
    }

    public String getInsSpot() {
        return insSpot;
    }

    public void setInsSpot(String insSpot) {
        this.insSpot = insSpot;
    }

    public String getInspectors() {
        return inspectors;
    }

    public void setInspectors(String inspectors) {
        this.inspectors = inspectors;
    }

    public Long getInspectorId() {
        return inspectorId;
    }

    public void setInspectorId(Long inspectorId) {
        this.inspectorId = inspectorId;
    }

    public String getWitness() {
        return witness;
    }

    public void setWitness(String witness) {
        this.witness = witness;
    }

    public String getWitnessWorkUnit() {
        return witnessWorkUnit;
    }

    public void setWitnessWorkUnit(String witnessWorkUnit) {
        this.witnessWorkUnit = witnessWorkUnit;
    }

    public String getWitnessPosition() {
        return witnessPosition;
    }

    public void setWitnessPosition(String witnessPosition) {
        this.witnessPosition = witnessPosition;
    }

    public String getInspectionItem() {
        return inspectionItem;
    }

    public void setInspectionItem(String inspectionItem) {
        this.inspectionItem = inspectionItem;
    }

    public String getSiteCondition() {
        return siteCondition;
    }

    public void setSiteCondition(String siteCondition) {
        this.siteCondition = siteCondition;
    }

    public String getAssistanceReason() {
        return assistanceReason;
    }

    public void setAssistanceReason(String assistanceReason) {
        this.assistanceReason = assistanceReason;
    }

    public String getAssistanceMatters() {
        return assistanceMatters;
    }

    public void setAssistanceMatters(String assistanceMatters) {
        this.assistanceMatters = assistanceMatters;
    }

    public String getEntrustedUnitName() {
        return entrustedUnitName;
    }

    public void setEntrustedUnitName(String entrustedUnitName) {
        this.entrustedUnitName = entrustedUnitName;
    }

    public String getCommissionType() {
        return commissionType;
    }

    public void setCommissionType(String commissionType) {
        this.commissionType = commissionType;
    }

    public String getCommissionMatters() {
        return commissionMatters;
    }

    public void setCommissionMatters(String commissionMatters) {
        this.commissionMatters = commissionMatters;
    }

    public Date getCommissionDate() {
        return commissionDate;
    }

    public void setCommissionDate(Date commissionDate) {
        this.commissionDate = commissionDate;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public String getComMeaType() {
        return comMeaType;
    }

    public void setComMeaType(String comMeaType) {
        this.comMeaType = comMeaType;
    }

    public Date getComMeaDecDate() {
        return comMeaDecDate;
    }

    public void setComMeaDecDate(Date comMeaDecDate) {
        this.comMeaDecDate = comMeaDecDate;
    }

    public Date getUncComMeaDecDate() {
        return uncComMeaDecDate;
    }

    public void setUncComMeaDecDate(Date uncComMeaDecDate) {
        this.uncComMeaDecDate = uncComMeaDecDate;
    }

    public Date getUncDate() {
        return uncDate;
    }

    public void setUncDate(Date uncDate) {
        this.uncDate = uncDate;
    }

    public Integer getComMeaDuration() {
        return comMeaDuration;
    }

    public void setComMeaDuration(Integer comMeaDuration) {
        this.comMeaDuration = comMeaDuration;
    }

    public String getStorageLocation() {
        return storageLocation;
    }

    public void setStorageLocation(String storageLocation) {
        this.storageLocation = storageLocation;
    }

    public Date getNoticeDate() {
        return noticeDate;
    }

    public void setNoticeDate(Date noticeDate) {
        this.noticeDate = noticeDate;
    }

    public Boolean getStatementDefense() {
        return statementDefense;
    }

    public void setStatementDefense(Boolean statementDefense) {
        this.statementDefense = statementDefense;
    }

    public Boolean getHearing() {
        return hearing;
    }

    public void setHearing(Boolean hearing) {
        this.hearing = hearing;
    }

    public Date getHearingStartTime() {
        return hearingStartTime;
    }

    public void setHearingStartTime(Date hearingStartTime) {
        this.hearingStartTime = hearingStartTime;
    }

    public Date getHearingEndTime() {
        return hearingEndTime;
    }

    public void setHearingEndTime(Date hearingEndTime) {
        this.hearingEndTime = hearingEndTime;
    }

    public String getHearingLocation() {
        return hearingLocation;
    }

    public void setHearingLocation(String hearingLocation) {
        this.hearingLocation = hearingLocation;
    }

    public String getHearingChairman() {
        return hearingChairman;
    }

    public void setHearingChairman(String hearingChairman) {
        this.hearingChairman = hearingChairman;
    }

    public String getRecorder() {
        return recorder;
    }

    public void setRecorder(String recorder) {
        this.recorder = recorder;
    }

    public String getTranslator() {
        return translator;
    }

    public void setTranslator(String translator) {
        this.translator = translator;
    }

    public String getHearingBasicSituation() {
        return hearingBasicSituation;
    }

    public void setHearingBasicSituation(String hearingBasicSituation) {
        this.hearingBasicSituation = hearingBasicSituation;
    }

    public String getHandlingOpinionAndSuggestions() {
        return handlingOpinionAndSuggestions;
    }

    public void setHandlingOpinionAndSuggestions(String handlingOpinionAndSuggestions) {
        this.handlingOpinionAndSuggestions = handlingOpinionAndSuggestions;
    }

    public String getBehavior() {
        return behavior;
    }

    public void setBehavior(String behavior) {
        this.behavior = behavior;
    }

    public String getViolationOfRegulations() {
        return violationOfRegulations;
    }

    public void setViolationOfRegulations(String violationOfRegulations) {
        this.violationOfRegulations = violationOfRegulations;
    }

    public Date getDeadlineToCorrect() {
        return deadlineToCorrect;
    }

    public void setDeadlineToCorrect(Date deadlineToCorrect) {
        this.deadlineToCorrect = deadlineToCorrect;
    }

    public String getCorrectionContent() {
        return correctionContent;
    }

    public void setCorrectionContent(String correctionContent) {
        this.correctionContent = correctionContent;
    }

    public Date getCorrectionNoticeDate() {
        return correctionNoticeDate;
    }

    public void setCorrectionNoticeDate(Date correctionNoticeDate) {
        this.correctionNoticeDate = correctionNoticeDate;
    }

    public String getItemSource() {
        return itemSource;
    }

    public void setItemSource(String itemSource) {
        this.itemSource = itemSource;
    }

    public Date getProcessingDate() {
        return processingDate;
    }

    public void setProcessingDate(Date processingDate) {
        this.processingDate = processingDate;
    }

    public String getProcessingLocation() {
        return processingLocation;
    }

    public void setProcessingLocation(String processingLocation) {
        this.processingLocation = processingLocation;
    }

    public String getExecutingPerson() {
        return executingPerson;
    }

    public void setExecutingPerson(String executingPerson) {
        this.executingPerson = executingPerson;
    }

    public String getSupervisor() {
        return supervisor;
    }

    public void setSupervisor(String supervisor) {
        this.supervisor = supervisor;
    }

    public String getItemHandling() {
        return itemHandling;
    }

    public void setItemHandling(String itemHandling) {
        this.itemHandling = itemHandling;
    }

    public String getReviewRequest() {
        return reviewRequest;
    }

    public void setReviewRequest(String reviewRequest) {
        this.reviewRequest = reviewRequest;
    }

    public String getApplicationReviewItem() {
        return applicationReviewItem;
    }

    public void setApplicationReviewItem(String applicationReviewItem) {
        this.applicationReviewItem = applicationReviewItem;
    }

    public Date getApplicationDate() {
        return applicationDate;
    }

    public void setApplicationDate(Date applicationDate) {
        this.applicationDate = applicationDate;
    }

    public Date getAcceptingDate() {
        return acceptingDate;
    }

    public void setAcceptingDate(Date acceptingDate) {
        this.acceptingDate = acceptingDate;
    }

    public String getReviewAuthority() {
        return reviewAuthority;
    }

    public void setReviewAuthority(String reviewAuthority) {
        this.reviewAuthority = reviewAuthority;
    }

    public Date getReviewDecisionDate() {
        return reviewDecisionDate;
    }

    public void setReviewDecisionDate(Date reviewDecisionDate) {
        this.reviewDecisionDate = reviewDecisionDate;
    }

    public String getReviewDecisionType() {
        return reviewDecisionType;
    }

    public void setReviewDecisionType(String reviewDecisionType) {
        this.reviewDecisionType = reviewDecisionType;
    }

    public String getReviewDecisionResult() {
        return reviewDecisionResult;
    }

    public void setReviewDecisionResult(String reviewDecisionResult) {
        this.reviewDecisionResult = reviewDecisionResult;
    }

    public String getFilingPerson() {
        return filingPerson;
    }

    public void setFilingPerson(String filingPerson) {
        this.filingPerson = filingPerson;
    }

    public Date getArchivingDate() {
        return archivingDate;
    }

    public void setArchivingDate(Date archivingDate) {
        this.archivingDate = archivingDate;
    }

    public String getArchivingNumber() {
        return archivingNumber;
    }

    public void setArchivingNumber(String archivingNumber) {
        this.archivingNumber = archivingNumber;
    }

    public Long getRetentionPeriod() {
        return retentionPeriod;
    }

    public void setRetentionPeriod(Long retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public String getPenaltyDecisionNumber() {
        return penaltyDecisionNumber;
    }

    public void setPenaltyDecisionNumber(String penaltyDecisionNumber) {
        this.penaltyDecisionNumber = penaltyDecisionNumber;
    }

    public String getExecutionMethod() {
        return executionMethod;
    }

    public void setExecutionMethod(String executionMethod) {
        this.executionMethod = executionMethod;
    }

    public Date getCaseClosureDate() {
        return caseClosureDate;
    }

    public void setCaseClosureDate(Date caseClosureDate) {
        this.caseClosureDate = caseClosureDate;
    }

    public Date getExecutionDate() {
        return executionDate;
    }

    public void setExecutionDate(Date executionDate) {
        this.executionDate = executionDate;
    }

    public Long getExecutionAmount() {
        return executionAmount;
    }

    public void setExecutionAmount(Long executionAmount) {
        this.executionAmount = executionAmount;
    }

    public String getPartyType() {
        return partyType;
    }

    public void setPartyType(String partyType) {
        this.partyType = partyType;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getPriPID() {
        return priPID;
    }

    public void setPriPID(String priPID) {
        this.priPID = priPID;
    }

    public String getUniSCID() {
        return uniSCID;
    }

    public void setUniSCID(String uniSCID) {
        this.uniSCID = uniSCID;
    }

    public String getAddr() {
        return addr;
    }

    public void setAddr(String addr) {
        this.addr = addr;
    }

    public String getDom() {
        return dom;
    }

    public void setDom(String dom) {
        this.dom = dom;
    }

    public String getUnitTel() {
        return unitTel;
    }

    public void setUnitTel(String unitTel) {
        this.unitTel = unitTel;
    }

    public String getCerType() {
        return cerType;
    }

    public void setCerType(String cerType) {
        this.cerType = cerType;
    }

    public String getCerNo() {
        return cerNo;
    }

    public void setCerNo(String cerNo) {
        this.cerNo = cerNo;
    }

    public String getPartyOtherContactInfo() {
        return partyOtherContactInfo;
    }

    public void setPartyOtherContactInfo(String partyOtherContactInfo) {
        this.partyOtherContactInfo = partyOtherContactInfo;
    }

    public String getLeRep() {
        return leRep;
    }

    public void setLeRep(String leRep) {
        this.leRep = leRep;
    }

    public String getLeRepCerType() {
        return leRepCerType;
    }

    public void setLeRepCerType(String leRepCerType) {
        this.leRepCerType = leRepCerType;
    }

    public String getLeRepCerNO() {
        return leRepCerNO;
    }

    public void setLeRepCerNO(String leRepCerNO) {
        this.leRepCerNO = leRepCerNO;
    }

    public String getLeRepAddr() {
        return leRepAddr;
    }

    public void setLeRepAddr(String leRepAddr) {
        this.leRepAddr = leRepAddr;
    }

    public String getLeRepPhone() {
        return leRepPhone;
    }

    public void setLeRepPhone(String leRepPhone) {
        this.leRepPhone = leRepPhone;
    }

    public String getDocumentDeliveryAddress() {
        return documentDeliveryAddress;
    }

    public void setDocumentDeliveryAddress(String documentDeliveryAddress) {
        this.documentDeliveryAddress = documentDeliveryAddress;
    }

    public String getElectronicEvidence() {
        return electronicEvidence;
    }

    public void setElectronicEvidence(String electronicEvidence) {
        this.electronicEvidence = electronicEvidence;
    }

    public String getLegalCompanyPosition() {
        return legalCompanyPosition;
    }

    public void setLegalCompanyPosition(String legalCompanyPosition) {
        this.legalCompanyPosition = legalCompanyPosition;
    }

    public String getPartyAttitude() {
        return partyAttitude;
    }

    public void setPartyAttitude(String partyAttitude) {
        this.partyAttitude = partyAttitude;
    }

    public Boolean getNeedComMea() {
        return needComMea;
    }

    public void setNeedComMea(Boolean needComMea) {
        this.needComMea = needComMea;
    }

    public String getComMeaBasis() {
        return comMeaBasis;
    }

    public void setComMeaBasis(String comMeaBasis) {
        this.comMeaBasis = comMeaBasis;
    }

    public String getComMeaReason() {
        return comMeaReason;
    }

    public void setComMeaReason(String comMeaReason) {
        this.comMeaReason = comMeaReason;
    }

    public String getIllegFact() {
        return illegFact;
    }

    public void setIllegFact(String illegFact) {
        this.illegFact = illegFact;
    }

    public String getIllegActType() {
        return illegActType;
    }

    public void setIllegActType(String illegActType) {
        this.illegActType = illegActType;
    }

    public String getIllegActTypeName() {
        return illegActTypeName;
    }

    public void setIllegActTypeName(String illegActTypeName) {
        this.illegActTypeName = illegActTypeName;
    }

    public String getPenBasisName() {
        return penBasisName;
    }

    public void setPenBasisName(String penBasisName) {
        this.penBasisName = penBasisName;
    }

    public String getDiscretionFact() {
        return discretionFact;
    }

    public void setDiscretionFact(String discretionFact) {
        this.discretionFact = discretionFact;
    }

    public Boolean getInclusivePrudent() {
        return inclusivePrudent;
    }

    public void setInclusivePrudent(Boolean inclusivePrudent) {
        this.inclusivePrudent = inclusivePrudent;
    }

    public Boolean getMajorCase() {
        return majorCase;
    }

    public void setMajorCase(Boolean majorCase) {
        this.majorCase = majorCase;
    }

    public Boolean getNetworkTrade() {
        return networkTrade;
    }

    public void setNetworkTrade(Boolean networkTrade) {
        this.networkTrade = networkTrade;
    }

    public Boolean getEmergency() {
        return emergency;
    }

    public void setEmergency(Boolean emergency) {
        this.emergency = emergency;
    }

    public Boolean getDiscretion() {
        return discretion;
    }

    public void setDiscretion(Boolean discretion) {
        this.discretion = discretion;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public Date getDiscretionInformTime() {
        return discretionInformTime;
    }

    public void setDiscretionInformTime(Date discretionInformTime) {
        this.discretionInformTime = discretionInformTime;
    }

    public String getDiscretionMethod() {
        return discretionMethod;
    }

    public void setDiscretionMethod(String discretionMethod) {
        this.discretionMethod = discretionMethod;
    }

    public String getDiscretionBasis() {
        return discretionBasis;
    }

    public void setDiscretionBasis(String discretionBasis) {
        this.discretionBasis = discretionBasis;
    }

    public String getMitigationBasis() {
        return mitigationBasis;
    }

    public void setMitigationBasis(String mitigationBasis) {
        this.mitigationBasis = mitigationBasis;
    }

    public Integer getPenaltyDiscretionPoints() {
        return penaltyDiscretionPoints;
    }

    public void setPenaltyDiscretionPoints(Integer penaltyDiscretionPoints) {
        this.penaltyDiscretionPoints = penaltyDiscretionPoints;
    }

    public String getDiscretionMaterials() {
        return discretionMaterials;
    }

    public void setDiscretionMaterials(String discretionMaterials) {
        this.discretionMaterials = discretionMaterials;
    }

    public String getPenContent() {
        return penContent;
    }

    public void setPenContent(String penContent) {
        this.penContent = penContent;
    }

    public String getPenalDec() {
        return penalDec;
    }

    public void setPenalDec(String penalDec) {
        this.penalDec = penalDec;
    }

    public Date getPenalDecIssDate() {
        return penalDecIssDate;
    }

    public void setPenalDecIssDate(Date penalDecIssDate) {
        this.penalDecIssDate = penalDecIssDate;
    }

    public String getPenalDecIssRea() {
        return penalDecIssRea;
    }

    public void setPenalDecIssRea(String penalDecIssRea) {
        this.penalDecIssRea = penalDecIssRea;
    }

    public String getPenType() {
        return penType;
    }

    public void setPenType(String penType) {
        this.penType = penType;
    }

    public BigDecimal getCaseVal() {
        return caseVal;
    }

    public void setCaseVal(BigDecimal caseVal) {
        this.caseVal = caseVal;
    }

    public BigDecimal getPenAm() {
        return penAm;
    }

    public void setPenAm(BigDecimal penAm) {
        this.penAm = penAm;
    }

    public BigDecimal getOrdRefAm() {
        return ordRefAm;
    }

    public void setOrdRefAm(BigDecimal ordRefAm) {
        this.ordRefAm = ordRefAm;
    }

    public BigDecimal getForfAm() {
        return forfAm;
    }

    public void setForfAm(BigDecimal forfAm) {
        this.forfAm = forfAm;
    }

    public String getForfGoods() {
        return forfGoods;
    }

    public void setForfGoods(String forfGoods) {
        this.forfGoods = forfGoods;
    }

    public String getPenalRemark() {
        return penalRemark;
    }

    public void setPenalRemark(String penalRemark) {
        this.penalRemark = penalRemark;
    }

    public String getLawAuditStatus() {
        return lawAuditStatus;
    }

    public void setLawAuditStatus(String lawAuditStatus) {
        this.lawAuditStatus = lawAuditStatus;
    }

    public Date getExtComMeaDecDate() {
        return extComMeaDecDate;
    }

    public void setExtComMeaDecDate(Date extComMeaDecDate) {
        this.extComMeaDecDate = extComMeaDecDate;
    }

    public Integer getExtDuration() {
        return extDuration;
    }

    public void setExtDuration(Integer extDuration) {
        this.extDuration = extDuration;
    }

    public String getExtComMeaBasis() {
        return extComMeaBasis;
    }

    public void setExtComMeaBasis(String extComMeaBasis) {
        this.extComMeaBasis = extComMeaBasis;
    }

    public String getQualification() {
        return qualification;
    }

    public void setQualification(String qualification) {
        this.qualification = qualification;
    }
}
