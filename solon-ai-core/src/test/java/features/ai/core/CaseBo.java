package features.ai.core;

/**
 * @author noear 2025/6/11 created
 */

import lombok.Data;
import org.noear.solon.annotation.Param;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 案件信息业务对象 zf_case
 *
 * @author chengliang4810
 * @date 2025-02-17
 */
@Data
public class CaseBo {
    @Param(description = "案件ID, 传递时使用String类型")
    private Long caseId;

    @Param(description = "行政强制措施期限")
    private Integer comMeaDuration;

    @Param(description = "aFloat1")
    private Float aFloat1;

    @Param(description = "aDouble1")
    private Double aDouble1;

    @Param(description = "自由裁量情况总结")
    private String discretionSummary;

    @Param(description = "案件的拓展数据")
    private Map<String, Object> ext;

    @Param(description = "alist1")
    private List<String> alist1;

    @Param(description = "行政处罚告知时间")
    private Date penaltyNoticeTime;

    @Param(description = "是否陈述申辩")
    private Boolean needDefense;

    @Param(description = "陈述申辩记录人")
    private String defenseRecorder;

    @Param(description = "陈述申辩人")
    private String defensePerson;

    @Param(description = "案值（万元）")
    private BigDecimal caseVal;

    @Param(description = "案值2（万元）")
    private BigInteger caseVal2;
}
