CON_DATA = $D000
EXIT     = $D300

        .defsection code, $0200
        .section code
start:  ldx #0
@loop:  lda msg,x
        beq done
        sta CON_DATA
        inx
        bne @loop
done:   lda #0
        sta EXIT

msg:    .byte "Hello, 6502!", 10, 0
