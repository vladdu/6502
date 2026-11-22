CON_DATA = $D000
EXIT     = $D300

        .defsection code, $0200
        .defsection data, $0280

        .section code
start:  ldx #0
@loop:  lda msg,x
        beq done
        sta CON_DATA
        inx
        bne @loop
done:   lda #0
        sta EXIT

        .section data
msg:    .byte "Hello, 6502!", 10, 0
