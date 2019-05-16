package io.stormbird.wallet.service;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.stormbird.token.entity.*;
import io.stormbird.token.tools.TokenDefinition;
import io.stormbird.wallet.R;
import io.stormbird.wallet.entity.NetworkInfo;
import io.stormbird.wallet.entity.Token;
import io.stormbird.wallet.entity.Wallet;
import io.stormbird.wallet.interact.SetupTokensInteract;
import io.stormbird.wallet.repository.EthereumNetworkRepositoryType;
import io.stormbird.wallet.repository.entity.RealmAuxData;
import io.stormbird.wallet.ui.HomeActivity;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static android.os.FileObserver.ALL_EVENTS;
import static io.stormbird.wallet.C.ADDED_TOKEN;
import static io.stormbird.wallet.viewmodel.HomeViewModel.ALPHAWALLET_DIR;
import static org.web3j.crypto.WalletUtils.isValidAddress;
import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;

/**
 * AssetDefinitionService is the only place where we interface with XML files.
 * This is to avoid duplicating resources
 * and also provide a consistent way to get XML values
 */

public class AssetDefinitionService implements ParseResult
{
    private static final String XML_EXT = "xml";
    private final Context context;
    private final OkHttpClient okHttpClient;

    private Map<Integer, Map<String, TokenDefinition>> assetDefinitions; //Mapping of contract address to token definitions
    private Map<String, Long> assetChecked;                //Mapping of contract address to when they were last fetched from server
    private Map<Integer, List<String>> devOverrideContracts;             //List of contract addresses which have been overridden by definition in developer folder
    private FileObserver fileObserver;                     //Observer which scans the override directory waiting for file change
    private Map<String, String> fileHashes;                //Mapping of files and hashes.

    private final NotificationService notificationService;
    private final RealmManager realmManager;
    private final EthereumNetworkRepositoryType ethereumNetworkRepository;
    private Web3j web3j;
    private int web3ChainId;

    public AssetDefinitionService(OkHttpClient client, Context ctx, NotificationService svs, RealmManager rm, EthereumNetworkRepositoryType eth)
    {
        context = ctx;
        okHttpClient = client;
        assetChecked = new HashMap<>();
        devOverrideContracts = new ConcurrentHashMap<>();
        fileHashes = new ConcurrentHashMap<>();
        notificationService = svs;
        realmManager = rm;
        ethereumNetworkRepository = eth;
        web3ChainId = 0;

        loadLocalContracts();
    }

    private void loadLocalContracts()
    {
        assetDefinitions = new HashMap<>();

        try
        {
            assetDefinitions.clear();
            loadContracts(context.getFilesDir(), false);
            checkDownloadedFiles();
        }
        catch (IOException| SAXException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Fetches non fungible token definition given contract address and token ID
     * @param contractAddress
     * @param v
     * @return
     */
    public NonFungibleToken getNonFungibleToken(Token token, String contractAddress, BigInteger v)
    {
        TokenDefinition definition = getAssetDefinition(token.tokenInfo.chainId, contractAddress);
        if (definition != null)
        {
            Map<String, FunctionDefinition> tokenIdResults = token.getTokenIdResults(v);
            return new NonFungibleToken(v, definition, tokenIdResults);
        }
        else
        {
            return null;
        }
    }

    public TokenScriptResult getTokenScriptResult(Token token)
    {
        return getTokenScriptResult(token, BigInteger.ZERO);
    }

    public List<AttributeType> getAttrs(Token token)
    {
        TokenDefinition definition = getAssetDefinition(token.tokenInfo.chainId, token.tokenInfo.address);
        if (definition != null)
        {
            return new ArrayList<>(definition.attributeTypes.values());
        }
        else
        {
            return null;
        }
    }

    public Observable<TokenScriptResult.Attribute> getTokenScriptResultR(Token token, BigInteger tokenId)
    {
        TokenDefinition definition = getAssetDefinition(token.tokenInfo.chainId, token.tokenInfo.address);
        if (definition == null) return Observable.fromCallable(() -> null);
        List<AttributeType> attrs = new ArrayList<>(definition.attributeTypes.values());

        return Observable.fromIterable(attrs)
                .flatMap(attr -> fetchAttrResult(attr, tokenId, token));
    }

    private Observable<TokenScriptResult.Attribute> staticAttribute(AttributeType attr, BigInteger tokenId)
    {
        return Observable.fromCallable(() -> {
            try
            {
                BigInteger val = tokenId.and(attr.bitmask).shiftRight(attr.bitshift);
                return new TokenScriptResult.Attribute(attr.id, attr.name, val, attr.toString(val));
            }
            catch (Exception e)
            {
                return new TokenScriptResult.Attribute(attr.id, attr.name, tokenId, "unsupported encoding");
            }
        });
    }

    public Observable<TokenScriptResult.Attribute> fetchAttrResult(AttributeType attr, BigInteger tokenId, Token token)
    {
        if (attr.function == null)
        {
            return staticAttribute(attr, tokenId);
        }
        else
        {
            ContractAddress contract;
            List<String> contracts = attr.function.contract.addresses.get(token.tokenInfo.chainId);
            if (contracts.contains(token.tokenInfo.address))
            {
                contract = new ContractAddress(token.tokenInfo.chainId, token.tokenInfo.address);
            }
            else
            {
                contract = getFromContracts(attr.function.contract.addresses);
            }
            if (contract == null) return Observable.fromCallable(() -> null);
            FunctionDefinition functionDef = getFunctionResult(token.getWallet(), contract, attr.function.method, tokenId);
            if (functionDef == null)
            {
                return fetchResultFromEthereum(token.getWallet(), contract, attr, tokenId);
            }
            else
            {
                return resultFromDatabase(functionDef, attr);
            }
        }
    }

    /**
     * Haven't pre-cached this value yet, so need to fetch it before we can proceed
     * @param wallet
     * @param contract
     * @param attr
     * @param tokenId
     * @return
     */
    private Observable<TokenScriptResult.Attribute> fetchResultFromEthereum(String wallet, ContractAddress contract, AttributeType attr, BigInteger tokenId)
    {
        return Observable.fromCallable(() -> {
            // 1: create transaction call
            org.web3j.abi.datatypes.Function transaction = attr.function.generateTransactionFunction(wallet, tokenId);
            // 2: create web3 connection
            if (web3ChainId != contract.chainId)
            {
                OkHttpClient okClient = new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .writeTimeout(5, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build();

                HttpService nodeService = new HttpService(ethereumNetworkRepository.getNetworkByChain(contract.chainId).rpcServerUrl, okClient, false);

                web3j = Web3j.build(nodeService);
                web3ChainId = contract.chainId;
            }
            //now push the transaction
            String result = callSmartContractFunction(transaction, contract.address, new Wallet(wallet));
            TransactionResult transactionResult = new TransactionResult(contract.chainId, contract.address, tokenId, attr.function.method);

            attr.function.handleTransactionResult(transactionResult, transaction, result);

            String res = transactionResult.result;
            BigInteger val = tokenId;
            if (attr.syntax == TokenDefinition.Syntax.NumericString)
            {
                if (transactionResult.result.startsWith("0x"))
                    res = res.substring(2);
                val = new BigInteger(res, 16);
            }
            return new TokenScriptResult.Attribute(attr.id, attr.name, val, res);
        });
    }

    private String callSmartContractFunction(
            Function function, String contractAddress, Wallet wallet) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);

        try
        {
            org.web3j.protocol.core.methods.request.Transaction transaction
                    = createEthCallTransaction(wallet.address, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            return response.getValue();
        }
        catch (IOException e) //this call is expected to be interrupted when user switches network or wallet
        {
            return null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private Observable<TokenScriptResult.Attribute> resultFromDatabase(FunctionDefinition functionDef, AttributeType attr)
    {
        return Observable.fromCallable(() -> {
            String res = functionDef.result;
            BigInteger val = BigInteger.ZERO;
            if (attr.syntax == TokenDefinition.Syntax.NumericString)
            {
                if (res.startsWith("0x"))
                    res = res.substring(2);
                val = new BigInteger(res, 16);
            }
            return new TokenScriptResult.Attribute(attr.id, attr.name, val, res);
        });
    }

    public int getAttrCount(Token token)
    {
        TokenDefinition definition = getAssetDefinition(token.tokenInfo.chainId, token.tokenInfo.address);
        return definition.attributeTypes.size();
    }

    public TokenScriptResult getTokenScriptResult(Token token, BigInteger tokenId)
    {
        TokenScriptResult result = new TokenScriptResult();
        TokenDefinition definition = getAssetDefinition(token.tokenInfo.chainId, token.tokenInfo.address);
        if (definition != null)
        {
            //use a stream to get the result here
            for (String key : definition.attributeTypes.keySet()) {
                AttributeType attrtype = definition.attributeTypes.get(key);
                ContractAddress contract;

                try
                {
                    BigInteger val = tokenId;
                    if (attrtype.function != null)
                    {
                        //determine contract address
                        List<String> contracts = attrtype.function.contract.addresses.get(token.tokenInfo.chainId);
                        if (contracts.contains(token.tokenInfo.address))
                        {
                            contract = new ContractAddress(token.tokenInfo.chainId, token.tokenInfo.address);
                        }
                        else
                        {
                            contract = getFromContracts(attrtype.function.contract.addresses);
                        }

                        if (contract == null)
                            continue;
                        FunctionDefinition functionDef = getFunctionResult(token.getWallet(), contract, attrtype.function.method, tokenId); //t.getTokenIdResults(BigInteger.ZERO);
                        String res = functionDef.result;
                        if (attrtype.syntax == TokenDefinition.Syntax.NumericString)
                        {
                            if (res.startsWith("0x"))
                                res = res.substring(2);
                            val = new BigInteger(res, 16);
                        }
                        result.setAttribute(attrtype.id,
                                           new TokenScriptResult.Attribute(attrtype.id, attrtype.name, val, res));
                    }
                    else
                    {
                        val = tokenId.and(attrtype.bitmask).shiftRight(attrtype.bitshift);
                        result.setAttribute(attrtype.id,
                                           new TokenScriptResult.Attribute(attrtype.id, attrtype.name, val, attrtype.toString(val)));
                    }
                }
                catch (Exception e)
                {
                    result.setAttribute(attrtype.id,
                                       new TokenScriptResult.Attribute(attrtype.id, attrtype.name, tokenId, "unsupported encoding"));
                }
            }
        }

        return result;
    }

    private ContractAddress getFromContracts(Map<Integer, List<String>> addresses)
    {
        for (int chainId : addresses.keySet())
        {
            for (String addr : addresses.get(chainId))
            {
                return new ContractAddress(chainId, addr);
            }
        }

        return null;
    }

    private Token getTokenFromContracts(TokensService service, Map<Integer, List<String>> addresses)
    {
        Token t = null;
        for (int chainId : addresses.keySet())
        {
            for (String addr : addresses.get(chainId))
            {
                t = service.getToken(chainId, addr);
                if (t != null) return t;
            }
        }

        return null;
    }

    private String getContractNameFromDefiniton(Token token, TokenDefinition def)
    {
        for (String key : def.contracts.keySet())
        {
            ContractInfo info = def.contracts.get(key);
            for (String addr : info.addresses.get(token.tokenInfo.chainId))
            {
                if (addr.equals(token.tokenInfo.address))
                {
                    return key;
                }
            }
        }

        return "";
    }

    /**
     * Called at startup once we know we've got folder write permission.
     * Note - Android 6.0 and above needs user to verify folder access permission
     * TODO: if user doesn't give permission then use the app private folder and tell user they can't
     *  load contracts themselves
     */
    public void checkExternalDirectoryAndLoad()
    {
        //create XML repository directory
        File directory = new File(
                Environment.getExternalStorageDirectory()
                        + File.separator + ALPHAWALLET_DIR);

        if (!directory.exists())
        {
            directory.mkdir(); //does this throw if we haven't given permission?
        }

        loadExternalContracts(directory);
    }

    private TokenDefinition getDefinitionMapping(int chainId, String address)
    {
        Map<String, TokenDefinition> networkMap = assetDefinitions.get(chainId);
        if (networkMap != null)
        {
            return networkMap.get(address);
        }
        else
        {
            return null;
        }
    }

    /**
     * Get asset definition given contract address
     *
     * @param address
     * @return
     */
    public TokenDefinition getAssetDefinition(int chainId, String address)
    {
        TokenDefinition assetDef = null;
        String correctedAddress = address.toLowerCase(); //ensure address is in the format we want
        //is asset definition currently read?
        assetDef = getDefinitionMapping(chainId, correctedAddress);
        if (assetDef == null)
        {
            //try to load from the cache directory
            File xmlFile = getXMLFile(correctedAddress);

            //try web
            if (xmlFile == null)
            {
                loadScriptFromServer(correctedAddress); //this will complete asynchronously, and display will be updated
            }
            else
            {
                assetDef = loadTokenDefinition(correctedAddress);
                if (assetDef != null)
                {
                    assetDef.populateNetworks(assetDefinitions, devOverrideContracts);
                }
                else
                {
                    assetDef = null;
                }
            }
        }

        return assetDef; // if nothing found use default
    }

    /**
     * Function returns all contracts on this network ID
     *
     * @param networkId
     * @return
     */
    public List<String> getAllContracts(int networkId)
    {
        List<String> contractList = new ArrayList<>();
        Map<String, TokenDefinition> networkList = assetDefinitions.get(networkId);
        if (networkList != null)
        {
            for (String address : networkList.keySet())
            {
                if (!contractList.contains(address)) contractList.add(address);
            }
        }
        return contractList;
    }

    /**
     * Get the issuer name given the contract address
     * Note: this
     * @param token
     * @return
     */
    public String getIssuerName(Token token)
    {
        TokenDefinition definition = getDefinitionMapping(token.tokenInfo.chainId, token.getAddress());

        if (definition != null)
        {
            String issuer = definition.getKeyName();
            return (issuer == null || issuer.length() == 0) ? context.getString(R.string.stormbird) : issuer;
        }
        else
        {
            return token.getNetworkName();
        }
    }

    private void loadScriptFromServer(String correctedAddress)
    {
        //first check the last time we tried this session
        if (assetChecked.get(correctedAddress) == null || (System.currentTimeMillis() - assetChecked.get(correctedAddress)) > 1000*60*60)
        {
            Disposable d = fetchXMLFromServer(correctedAddress)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::handleFileLoad, this::onError);
        }
    }

    private void onError(Throwable throwable)
    {
        throwable.printStackTrace();
    }

    private TokenDefinition loadTokenDefinition(String address)
    {
        TokenDefinition definition = null;
        File xmlFile = getXMLFile(address.toLowerCase());
        try
        {
            if (xmlFile != null && xmlFile.exists())
            {
                FileInputStream is = new FileInputStream(xmlFile);
                definition = parseFile(is);
            }
        }
        catch (IOException|SAXException e)
        {
            e.printStackTrace();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return definition;
    }

    @SuppressWarnings("deprecation")
    private TokenDefinition parseFile(InputStream xmlInputStream) throws IOException, SAXException, Exception
    {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = context.getResources().getConfiguration().getLocales().get(0);
        }
        else
        {
            locale = context.getResources().getConfiguration().locale;
        }

        TokenDefinition definition = new TokenDefinition(
                xmlInputStream, locale, this);

        //now assign the networks
        if (definition.hasContracts())
        {
            assignNetworks(definition);
        }

        return definition;
    }

    private void assignNetworks(TokenDefinition definition)
    {
        if (definition != null)
        {
            //now map all contained addresses
            definition.populateNetworks(assetDefinitions, devOverrideContracts);
        }
    }

    /**
     * Add the contract addresses defined in the developer XML file to the override list.
     * Subsequent refresh of XML from server will not override these definitions.
     * @param definition interpreted definition
     */
    private void addOverrideFile(TokenDefinition definition)
    {
        if (definition != null)
        {
            definition.addToOverrides(devOverrideContracts);
        }
    }

    private void handleFileLoad(String address)
    {
        if (isValidAddress(address))
        {
            handleFile(address);
            context.sendBroadcast(new Intent(ADDED_TOKEN)); //inform walletview there is a new token
        }
    }

    /**
     * Add all contracts from this file into the assetDefinitions. Always override
     * @param address
     */
    private void handleFile(String address)
    {
        //this is file stored on the phone, notify to the main app to reload (use receiver)
        TokenDefinition tokenDefinition = loadTokenDefinition(address);
        if (tokenDefinition != null)
        {
            tokenDefinition.populateNetworks(assetDefinitions, null);
            tokenDefinition.addToOverrides(devOverrideContracts);
        }
    }

    private Observable<String> fetchXMLFromServer(String address)
    {
        return Observable.fromCallable(() -> {
            if (address.equals("")) return "0x";

            //peek to see if this file exists
            File existingFile = getXMLFile(address);
            long fileTime = 0;
            if (existingFile != null && existingFile.exists())
            {
                fileTime = existingFile.lastModified();
            }

            SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
            String dateFormat = format.format(new Date(fileTime));

            StringBuilder sb = new StringBuilder();
            sb.append("https://repo.aw.app/");
            sb.append(address);
            String result = null;

            //prepare Android headers
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(
                    context.getPackageName(), 0);
            String appVersion = info.versionName;
            String OSVersion = String.valueOf(Build.VERSION.RELEASE);

            okhttp3.Response response = null;

            try
            {
                Request request = new Request.Builder()
                        .url(sb.toString())
                        .get()
                        .addHeader("Accept", "text/xml; charset=UTF-8")
                        .addHeader("X-Client-Name", "AlphaWallet")
                        .addHeader("X-Client-Version", appVersion)
                        .addHeader("X-Platform-Name", "Android")
                        .addHeader("X-Platform-Version", OSVersion)
                        .addHeader("If-Modified-Since", dateFormat)
                        .build();

                response = okHttpClient.newCall(request).execute();

                String xmlBody = response.body().string();

                if (response.code() == HttpURLConnection.HTTP_OK && xmlBody != null && xmlBody.length() > 10)
                {
                    storeFile(address, xmlBody);
                    result = address;
                }
                else
                {
                    result = "0x";
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                if (response != null) response.body().close();
            }

            assetChecked.put(address, System.currentTimeMillis());

            return result;
        });
    }

    private File getExternalFile(String contractAddress)
    {
        File directory = new File(
                Environment.getExternalStorageDirectory()
                        + File.separator + ALPHAWALLET_DIR);

        if (directory.exists())
        {
            File externalFile = new File(directory, contractAddress.toLowerCase());
            if (externalFile.exists())
            {
                return externalFile;
            }
        }

        return null;
    }

    private void loadExternalContracts(File directory)
    {
        try
        {
            loadContracts(directory, true);
            startFileListener(directory);
        }
        catch (IOException|SAXException e)
        {
            e.printStackTrace();
        }
    }

    private void loadContracts(File directory, boolean external) throws IOException, SAXException
    {
        File[] files = directory.listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                if (f.getName().contains(".xml") || f.getName().contains(".tsml"))
                {
                    String extension = f.getName().substring(f.getName().lastIndexOf('.') + 1).toLowerCase();
                    if (extension.equals("xml") || extension.equals("tsml"))
                    {
                        try
                        {
                            if (f.getName().contains("entry2"))
                            {
                                System.out.println("door");
                            }
                            FileInputStream stream = new FileInputStream(f);
                            TokenDefinition td = parseFile(stream);
                            if (external) addOverrideFile(td);
                        }
                        catch (SAXParseException e)
                        {
                            e.printStackTrace();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    /**
     * Given contract address, find the corresponding File.
     * We have to search in the internal area and the external storage area
     * The reason we need two areas is prevent the need for normal users to have to give
     * permission to access external storage.
     * @param contractAddress
     * @return
     */
    private File getXMLFile(String contractAddress)
    {
        //build filename
        String fileName = contractAddress.toLowerCase() + "." + XML_EXT;

        //check
        File check = new File(context.getFilesDir(), fileName);
        if (check.exists())
        {
            return check;
        }
        else
        {
            return getExternalFile(contractAddress);
        }
    }

    private List<File> getFileList(File directory)
    {
        File[] files = context.getFilesDir().listFiles();
        return new ArrayList<File>(Arrays.asList(files));
    }

    private boolean isValidXML(File f)
    {
        int index = f.getName().lastIndexOf('.');
        if (index > 0)
        {
            String extension = f.getName().substring(index + 1).toLowerCase();
            String name = f.getName().substring(0, index).toLowerCase();
            return extension.equals("xml") && isValidAddress(name);
        }

        return false;
    }

    private String convertToAddress(File f)
    {
        return f.getName().substring(0, f.getName().lastIndexOf('.')).toLowerCase();
    }

    /**
     * check the downloaded XML files for updates when wallet restarts.
     */
    private void checkDownloadedFiles()
    {
        Disposable d = Observable.fromIterable(getFileList(context.getFilesDir()))
                .filter(this::isValidXML)
                .map(this::convertToAddress)
                .filter(this::notOverriden)
                .concatMap(this::fetchXMLFromServer)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleFile, this::onError);
    }

    private boolean notOverriden(String address)
    {
        //check all addresses
        for (int networkId : devOverrideContracts.keySet())
        {
            for (String addr : devOverrideContracts.get(networkId))
            {
                if (addr.equalsIgnoreCase(address))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean notOverriden(int networkId, String address)
    {
        return !(devOverrideContracts.containsKey(networkId) && devOverrideContracts.get(networkId).contains(address));
    }

    /**
     * Use internal directory to store contracts fetched from the server
     * @param address
     * @param result
     * @return
     * @throws
     */
    private File storeFile(String address, String result) throws IOException
    {
        String fName = address + ".xml";

        //Store received files in the internal storage area - no need to ask for permissions
        File file = new File(context.getFilesDir(), fName);

        FileOutputStream fos = new FileOutputStream(file);
        OutputStream os = new BufferedOutputStream(fos);
        os.write(result.getBytes());
        fos.flush();
        os.close();
        fos.close();
        return file;
    }

    public boolean hasDefinition(int networkId, String address)
    {
        TokenDefinition definition = getDefinitionMapping(networkId, address);
        if (definition != null)
        {
            return definition.hasNetwork(networkId);
        }
        else
        {
            return false;
        }
    }

    /**
     * For legacy
     * @param address
     * @return
     */
    public int getChainId(String address)
    {
        for (int network : assetDefinitions.keySet())
        {
            if (assetDefinitions.get(network).containsKey(address))
            {
                return network;
            }
        }

        return 0;
    }

    private Observable<String> checkFileTime(File localDefinition)
    {
        return Observable.fromCallable(() -> {
            String contractAddress = convertToAddress(localDefinition);
            URL url = new URL("https://repo.awallet.io/" + contractAddress);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setIfModifiedSince( localDefinition.lastModified() );

            switch (conn.getResponseCode())
            {
                case HttpURLConnection.HTTP_OK:
                    break;

                case HttpURLConnection.HTTP_NOT_MODIFIED:
                    contractAddress = "";
                    break;
            }

            conn.disconnect();
            return contractAddress;
        });
    }

    //when user reloads the tokens we should also check XML for any files
    public void clearCheckTimes()
    {
        assetChecked.clear();
    }

    public boolean hasTokenView(int chainId, String contractAddr)
    {
        TokenDefinition td = getAssetDefinition(chainId, contractAddr);
        return (td != null && td.attributeSets.containsKey("cards"));
    }

    public String getTokenView(int chainId, String contractAddr, String type)
    {
        String viewHTML = "";
        TokenDefinition td = getAssetDefinition(chainId, contractAddr);
        if (td != null && td.attributeSets.containsKey("cards"))
        {
            viewHTML = td.getCardData(type);
        }

        return viewHTML;
    }

    public Map<String, TSAction> getTokenFunctionMap(int chainId, String contractAddr)
    {
        TokenDefinition td = getAssetDefinition(chainId, contractAddr);
        if (td != null)
        {
            return td.getActions();
        }
        else
        {
            return null;
        }
    }

    public String getTokenFunctionView(int chainId, String contractAddr)
    {
        TokenDefinition td = getAssetDefinition(chainId, contractAddr);
        if (td != null && td.getActions().size() > 0)
        {
            for (TSAction a : td.getActions().values())
            {
                return a.view;
            }
            return null;
        }
        else
        {
            return null;
        }
    }

    public boolean hasAction(int chainId, String contractAddr)
    {
        TokenDefinition td = getAssetDefinition(chainId, contractAddr);
        if (td != null && td.actions != null && td.actions.size() > 0) return true;
        else return false;
    }

    @Override
    public void parseMessage(ParseResultId parseResult)
    {
        switch (parseResult)
        {
            case PARSER_OUT_OF_DATE:
                HomeActivity.setUpdatePrompt();
                break;
            case XML_OUT_OF_DATE:
                break;
            case OK:
                break;
        }
    }

    public void startFileListener(File path)
    {
        fileObserver = new FileObserver(path.getPath(), ALL_EVENTS)
        {
            @Override
            public void onEvent(int i, @Nullable String s)
            {
                //watch for new files and file change
                switch (i)
                {
                    case CREATE:
                    case MODIFY:
                        try
                        {
                            if (s.contains(".xml") || s.contains(".tsml"))
                            {
                                //form filename
                                File newTSFile = new File(
                                        Environment.getExternalStorageDirectory()
                                                + File.separator + ALPHAWALLET_DIR, s);
                                FileInputStream stream = new FileInputStream(newTSFile);
                                String hash = calcMD5(newTSFile);
                                String fileName = newTSFile.getAbsolutePath();
                                if (fileHashes.containsKey(fileName) && fileHashes.get(fileName).equals(hash))
                                {
                                    break;
                                }
                                fileHashes.put(fileName, hash);
                                TokenDefinition td = parseFile(stream);
                                addOverrideFile(td);
                                notificationService.DisplayNotification("Definition Updated", s, NotificationCompat.PRIORITY_MAX);
                            }
                        }
                        catch (SAXException e)
                        {
                            e.printStackTrace();
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        break;
                    default:
                        break;
                }
            }
        };

        fileObserver.startWatching();
    }

    private static String calcMD5(File file) throws IOException, NoSuchAlgorithmException
    {
        FileInputStream fis = new FileInputStream(file);
        MessageDigest digest = MessageDigest.getInstance("MD5");

        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        while ((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        };

        fis.close();

        byte[] bytes = digest.digest();

        //This bytes[] has bytes in decimal format;
        //Convert it to hexadecimal format
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes)
        {
            sb.append(Integer.toString((aByte & 0xff) + 0x100, 16).substring(1));
        }

        //return complete hash
        return sb.toString();
    }

    public Single<List<Token>> checkEthereumFunctions(SetupTokensInteract setupTokensInteract, TokensService tokensService)
    {
        //this.tokensInteract = setupTokensInteract;
        return Single.fromCallable(() -> {
            List<Token> updatedTokens = new ArrayList<>();
            for (int network : assetDefinitions.keySet())
            {
                Map<String, TokenDefinition> defMap = assetDefinitions.get(network);
                for (TokenDefinition td : defMap.values())
                {
//                    //need to go through all attribute-types and solve for each
//                    for (String key : td.attributeTypes.keySet()) {
//                        AttributeType attrtype = td.attributeTypes.get(key);
//                        BigInteger val = BigInteger.ZERO;
//                        if (attrtype.function != null)
//                        {
//                            //try to determine the value for this
//                        }
//

                    List<FunctionDefinition> fdList = td.getFunctionData();
                    for (FunctionDefinition fd : fdList)
                    {
                        List<String> addresses = fd.contract.addresses.get(network);
                        if (addresses != null)
                        {
                            for (String address : addresses)
                            {
                                //do we have this token?
                                Token token = tokensService.getToken(network, address);

                                //get information from contract, store and refresh the token data
                                if (token != null)
                                {
                                    token.processFunctionResults(fd, setupTokensInteract).blockingIterable()
                                            .forEach(tokensService::storeAuxData);
                                }
                            }
                        }
                    }
                }
            }
            return updatedTokens;
        });
    }


    //Database functions

    private String functionKey(ContractAddress cAddr, BigInteger tokenId, String method)
    {
        //produce a unique key for this. token address, token Id, chainId
        return cAddr.address + "-" + tokenId.toString(Character.MAX_RADIX) + "-" + cAddr.chainId + "-" + method;
    }

    public FunctionDefinition getFunctionResult(String walletAddress, ContractAddress contract, String method, BigInteger tokenId)
    {
        FunctionDefinition fd = null;
        try (Realm realm = realmManager.getAuxRealmInstance(walletAddress)) {
            RealmAuxData realmToken = realm.where(RealmAuxData.class)
                    .equalTo("instanceKey", functionKey(contract, tokenId, method))
                    .equalTo("chainId", contract.chainId)
                    .findFirst();

            if (realmToken != null)
            {
                fd = new FunctionDefinition();
                fd.method = method;
                fd.resultTime = realmToken.getResultTime();
                fd.result = realmToken.getResult();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return fd;
    }
}
