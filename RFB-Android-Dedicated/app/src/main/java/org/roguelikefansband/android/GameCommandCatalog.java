package org.roguelikefansband.android;

/** Stable Chinese command labels; actions are still sent to the selected upstream core. */
public final class GameCommandCatalog {
    private GameCommandCatalog() {
    }

    public static String label(char key, boolean ctrl) {
        if (ctrl) return ctrlLabel(Character.toUpperCase(key));
        switch (key) {
            case 'a': return "瞄准魔杖";
            case 'b': return "浏览法术";
            case 'c': return "关闭门";
            case 'd': return "丢弃物品";
            case 'e': return "装备列表";
            case 'f': return "射击";
            case 'g': return "拾取物品";
            case 'h': return "可映射键";
            case 'i': return "背包列表";
            case 'j': return "用钉堵门";
            case 'k': return "摧毁物品";
            case 'l': return "环顾四周";
            case 'm': return "施放法术";
            case 'n': return "可映射键";
            case 'o': return "打开门箱";
            case 'p': return "宠物命令";
            case 'q': return "喝药水";
            case 'r': return "读卷轴";
            case 's': return "搜索";
            case 't': return "脱下装备";
            case 'u': return "使用法杖";
            case 'v': return "投掷物品";
            case 'w': return "穿戴装备";
            case 'x': return "可映射键";
            case 'y': return "可映射键";
            case 'z': return "使用魔棒";

            case 'A': return "激活物品";
            case 'B': return "撞开门";
            case 'C': return "角色信息";
            case 'D': return "解除陷阱";
            case 'E': return "吃食物";
            case 'F': return "补充光源";
            case 'G': return "学习技能";
            case 'H': return "最近物品";
            case 'I': return "检查物品";
            case 'J': return "继续旅行";
            case 'K': return "可映射键";
            case 'L': return "定位地图";
            case 'M': return "完整地图";
            case 'N': return "可映射键";
            case 'O': return "物品列表";
            case 'P': return "物品袋";
            case 'Q': return "结束角色";
            case 'R': return "休息";
            case 'S': return "搜索模式";
            case 'T': return "挖掘";
            case 'U': return "种族能力";
            case 'V': return "版本信息";
            case 'W': return "交换戒指";
            case 'X': return "可映射键";
            case 'Y': return "怪物列表";
            case 'Z': return "自动探索";

            case ' ': return "继续/翻页";
            case '1': case '2': case '3': case '4': case '5':
            case '6': case '7': case '8': case '9': return "方向/数字选择";
            case '0': return "数字输入";
            case '!': return "读取偏好指令";
            case '@': return "宏设置";
            case '#': return "可映射符号";
            case '$': return "重载自动拾取";
            case '%': return "视觉设置";
            case '^': return "可映射符号";
            case '&': return "颜色设置";
            case '*': return "选择目标";
            case '(': return "可映射符号";
            case ')': return "保存屏幕";
            case '-': return "行走不拾取";
            case '_': return "编辑自动拾取";
            case '=': return "游戏设置";
            case '+': return "交互/改变地格";
            case '[': return "怪物列表";
            case ']': return "物品列表";
            case '{': return "给物品铭刻";
            case '}': return "移除铭刻";
            case '\\': return "可映射符号";
            case '|': return "可映射符号";
            case ';': return "行走并拾取";
            case ':': return "记录笔记";
            case '\'': case '"': return "文字/可映射";
            case '`': return "地图旅行";
            case '~': return "知识菜单";
            case ',': return "原地/拾取";
            case '<': return "上楼/荒野";
            case '.': return "开始奔跑";
            case '>': return "下楼/荒野";
            case '/': return "查询符号";
            case '?': return "帮助";
            default: return "直接输入";
        }
    }

    private static String ctrlLabel(char key) {
        switch (key) {
            case 'A': return "调试模式";
            case 'E': return "宏设置";
            case 'F': return "楼层感觉";
            case 'G': return "自动拾取";
            case 'I': return "切换信息窗";
            case 'O': return "物品列表";
            case 'P': return "历史消息";
            case 'Q': return "任务状态";
            case 'R': return "重绘屏幕";
            case 'S': return "保存游戏";
            case 'T': return "游戏时间";
            case 'V': return "视角居中";
            case 'W': case 'Y': return "巫师模式";
            case 'X': return "保存并退出";
            case 'Z': return "剧透信息";
            case '[': return "取消/返回（Esc）";
            case '\\': return "控制分隔符";
            case ']': return "控制分隔符";
            case '^': return "控制分隔符";
            case '_': return "控制分隔符";
            default: return "无原版 Ctrl 命令";
        }
    }
}
