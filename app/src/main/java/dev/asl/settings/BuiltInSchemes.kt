package dev.asl.settings

import dev.asl.core.ColorScheme

// Values from Windows Terminal's defaults.json; WT's implicit selectionBackground is #FFFFFF.
val BuiltInSchemes = listOf(
    ColorScheme(
        "Campbell", "#0C0C0C", "#CCCCCC", "#FFFFFF", "#FFFFFF",
        "#0C0C0C", "#C50F1F", "#13A10E", "#C19C00", "#0037DA", "#881798", "#3A96DD", "#CCCCCC",
        "#767676", "#E74856", "#16C60C", "#F9F1A5", "#3B78FF", "#B4009E", "#61D6D6", "#F2F2F2",
    ),
    ColorScheme(
        "Campbell Powershell", "#012456", "#CCCCCC", "#FFFFFF", "#FFFFFF",
        "#0C0C0C", "#C50F1F", "#13A10E", "#C19C00", "#0037DA", "#881798", "#3A96DD", "#CCCCCC",
        "#767676", "#E74856", "#16C60C", "#F9F1A5", "#3B78FF", "#B4009E", "#61D6D6", "#F2F2F2",
    ),
    ColorScheme(
        "One Half Dark", "#282C34", "#DCDFE4", "#FFFFFF", "#FFFFFF",
        "#282C34", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#DCDFE4",
        "#5A6374", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#DCDFE4",
    ),
    ColorScheme(
        "One Half Light", "#FAFAFA", "#383A42", "#4F525D", "#383A42",
        "#383A42", "#E45649", "#50A14F", "#C18301", "#0184BC", "#A626A4", "#0997B3", "#FAFAFA",
        "#4F525D", "#DF6C75", "#98C379", "#E4C07A", "#61AFEF", "#C577DD", "#56B5C1", "#FFFFFF",
    ),
    ColorScheme(
        "Solarized Dark", "#002B36", "#839496", "#FFFFFF", "#FFFFFF",
        "#002B36", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
        "#073642", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3",
    ),
    ColorScheme(
        "Solarized Light", "#FDF6E3", "#657B83", "#002B36", "#2C4D57",
        "#002B36", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
        "#073642", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3",
    ),
    ColorScheme(
        "Tango Dark", "#000000", "#D3D7CF", "#FFFFFF", "#FFFFFF",
        "#000000", "#CC0000", "#4E9A06", "#C4A000", "#3465A4", "#75507B", "#06989A", "#D3D7CF",
        "#555753", "#EF2929", "#8AE234", "#FCE94F", "#729FCF", "#AD7FA8", "#34E2E2", "#EEEEEC",
    ),
    ColorScheme(
        "Tango Light", "#FFFFFF", "#555753", "#000000", "#141414",
        "#000000", "#CC0000", "#4E9A06", "#C4A000", "#3465A4", "#75507B", "#06989A", "#D3D7CF",
        "#555753", "#EF2929", "#8AE234", "#FCE94F", "#729FCF", "#AD7FA8", "#34E2E2", "#EEEEEC",
    ),
    ColorScheme(
        "Vintage", "#000000", "#C0C0C0", "#FFFFFF", "#FFFFFF",
        "#000000", "#800000", "#008000", "#808000", "#000080", "#800080", "#008080", "#C0C0C0",
        "#808080", "#FF0000", "#00FF00", "#FFFF00", "#0000FF", "#FF00FF", "#00FFFF", "#FFFFFF",
    ),
    // From github.com/dracula/windows-terminal, which defines selectionBackground.
    ColorScheme(
        "Dracula", "#282A36", "#F8F8F2", "#F8F8F2", "#44475A",
        "#21222C", "#FF5555", "#50FA7B", "#F1FA8C", "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
        "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5", "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF",
    ),
)
