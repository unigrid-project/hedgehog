package com.huzu.hedgehog;

import com.google.gson.GsonBuilder;
import java.security.Security;
import java.util.ArrayList;

public class darkhedgehog {

    public static ArrayList<Block> blockchain = new ArrayList<Block>();
    public static int difficulty = 9;
    public static Wallet walletA;
    public static Wallet walletB;

    public static void main(String[] args) {
        //Setup Bouncey castle as a Security Provider
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        //Create the new wallets
        walletA = new Wallet();
        walletB = new Wallet();
        System.out.println("Private and public keys");
        System.out.println(StringUtil.getStringFromKey(walletA.privateKey));
        System.out.println(StringUtil.getStringFromKey(walletB.privateKey));
        Transaction transaction = new Transaction(walletA.publicKey, walletB.publicKey, 5, null);
        transaction.generateSignature(walletA.privateKey);
        //Verify the signature works and verify it from the public key
        System.out.println("Is signature verified");
        System.out.println(transaction.verifySignature());
        //Block genesd(new Block("Hi this is the first block", "0"));isBlock = new Block("Hi this is the first block", "0");
        /*blockchain.add(new Block("Hi this is the first block", "0"));
        blockchain.get(0).mineBlock(difficulty);
        // System.out.println("Hash for block 1 : " + genesisBlock.hash);
        //System.out.println("Hash for block 1 : " + genesisBlock.hash);
        blockchain.add(new Block("Yo its the Second hedgehog:", blockchain.get(blockchain.size() - 1).hash));
        blockchain.get(1).mineBlock(difficulty);

        blockchain.add(new Block("Yo its the Third hedgehog:", blockchain.get(blockchain.size() - 1).hash));
        blockchain.get(2).mineBlock(difficulty);
        System.out.println("\nBlockchain is Valid: " + isChainValid());

        String blockchainJson = new GsonBuilder().setPrettyPrinting().create().toJson(blockchain);
        System.out.println("Hedgehog: " + blockchainJson);*/
    }

    public static Boolean isChainValid() {
        Block previousBlock;
        Block currentBlock;

        for (int i = 1; i < blockchain.size(); i++) {

            currentBlock = blockchain.get(i);
            previousBlock = blockchain.get(i - 1);
            if (!currentBlock.hash.equals(currentBlock.calculateHash())) {
                System.out.println("Current Block Hash: " + currentBlock.hash);
                return false;
            }
            if (!previousBlock.hash.equals(previousBlock.calculateHash())) {
                System.out.println("Current Block Hash: " + currentBlock.hash);
                return false;
            }
        }
        return true;
    }
}
