package features.expr;

import org.noear.solon.expression.ExpressionNode;
import org.noear.solon.expression.query.*;

/**
 * @author noear 2025/3/12 created
 */
public class PrintUtil {
    /**
     * 打印
     */
    public static void printTree(ExpressionNode node) {
        printTreeDo(node, 0);
    }

    static void printTreeDo(ExpressionNode node, int level) {
        if (node instanceof FieldNode) {
            System.out.println(prefix(level) + "Field: " + ((FieldNode) node).getFieldName());
        } else if (node instanceof ValueNode) {
            System.out.println(prefix(level) + "Value: " + ((ValueNode) node).getValue());
        } else if (node instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) node;
            System.out.println(prefix(level) + "Comparison: " + compNode.getOperator());

            printTreeDo(compNode.getField(), level + 1);
            printTreeDo(compNode.getValue(), level + 1);
        } else if (node instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) node;
            System.out.println(prefix(level) + "Logical: " + opNode.getOperator());

            printTreeDo(opNode.getLeft(), level + 1);
            printTreeDo(opNode.getRight(), level + 1);
        }
    }

    static String prefix(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("  ");
        }

        return sb.toString();
    }


    /// ////////


    public static void printTree2(ExpressionNode node) {
        StringBuilder buf = new StringBuilder();
        printTree2Do(node, buf);
        System.out.println(buf);
    }

    static void printTree2Do(ExpressionNode node, StringBuilder buf) {
        if (node instanceof FieldNode) {
            buf.append(((FieldNode) node).getFieldName());
        } else if (node instanceof ValueNode) {
            buf.append(((ValueNode) node).getValue());
        } else if (node instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) node;

            buf.append("(");
            printTree2Do(compNode.getField(), buf);
            buf.append(" " + compNode.getOperator().getCode() + " ");
            printTree2Do(compNode.getValue(), buf);
            buf.append(")");

        } else if (node instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) node;

            buf.append("(");

            if (opNode.getRight() != null) {
                //二元
                printTree2Do(opNode.getLeft(), buf);
                buf.append(" " + opNode.getOperator().getCode() + " ");
                printTree2Do(opNode.getRight(), buf);
            } else {
                //一元
                buf.append(opNode.getOperator().getCode() + " ");
                printTree2Do(opNode.getLeft(), buf);
            }
            buf.append(")");
        }
    }
}