LOAD DATA
TRUNCATE INTO TABLE GUT_ISSUER_INFO
TRAILING NULLCOLS (
    ISUR_NO                    POSITION(1:12)                 "TRIM(:ISUR_NO)",
    ISUR_NM                    POSITION(13:372)               "TRIM(:ISUR_NM)",
    UNT_SCL_CDT_CD             POSITION(373:390)              "TRIM(:UNT_SCL_CDT_CD)",
    GXH                        POSITION(391:397)              "TRIM(:GXH)",
    WHGYBH                     POSITION(398:403)              "TRIM(:WHGYBH)",
    KHSSGBHY                   POSITION(404:408)              "TRIM(:KHSSGBHY)"
)
