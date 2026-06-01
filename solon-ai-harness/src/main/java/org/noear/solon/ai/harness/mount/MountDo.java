package org.noear.solon.ai.harness.mount;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.solon.ai.skills.cli.PoolType;

import java.io.Serializable;

/**
 *
 * @author noear 2026/5/31 created
 *
 */
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class MountDo implements Serializable {
    //类型
    private PoolType type = PoolType.SKILLS;

    //配置地址支持 "~/"（用户目录相对位置） 和 "./"（工作区相对位置）
    private String path;

    //是否启用
    private boolean enabled = true;
}
