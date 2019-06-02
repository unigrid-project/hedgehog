package com.huzu.hedgehog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;

public class darkhedgehog {

    private static final Logger logger = LogManager.getLogger("Base58CheckEncoding");
    public static ArrayList<Block> blockchain = new ArrayList<Block>();

    public static void main(String[] args) {
        //Block genesd(new Block("Hi this is the first block", "0"));isBlock = new Block("Hi this is the first block", "0");
        blockchain.add(new Block("Hi this is the first block", "0"));
        // logger.info("Hash for block 1 : " + genesisBlock.hash);
        //System.out.println("Hash for block 1 : " + genesisBlock.hash);
        blockchain.add(new Block("Yo its the Second hedgehog:", blockchain.get(blockchain.size() - 1).hash));
        blockchain.add(new Block("Yo its the Third hedgehog:", blockchain.get(blockchain.size() - 1).hash));
        String blockchainJson = new GsonBuilder().setPrettyPrinting().create().toJson(blockchain);
        logger.info("Hedgehog: ", blockchainJson);
    }
}
