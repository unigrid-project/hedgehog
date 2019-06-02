package com.huzu.hedgehog;

import java.util.Date;

public class Block {

    public String hash;
    public String previousHash;
    public String data;
    private Long timeStamp;
    private int nonce;

    // Block Constructor
    public Block(String data, String previousHash) {
        this.data = data;
        this.previousHash = previousHash;
        this.timeStamp = new Date().getTime();

        this.hash = calculateHash();
    }

    private String calculateHash() {

        String calculatedhash = StringUtil.applySha256(
                previousHash
                + Long.toString(timeStamp)
                + Integer.toString(nonce)
                + data
        );
        return calculatedhash;
    }

}
