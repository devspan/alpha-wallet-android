package com.alphawallet.app.viewmodel;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.alphawallet.app.interact.FetchTransactionsInteract;
import com.alphawallet.app.service.AssetDefinitionService;
import com.alphawallet.app.service.OpenSeaService;
import com.alphawallet.app.service.TokensService;

import io.reactivex.annotations.NonNull;

public class Erc721AssetsViewModelFactory implements ViewModelProvider.Factory {

    private final FetchTransactionsInteract fetchTransactionsInteract;
    private final AssetDefinitionService assetDefinitionService;
    private final TokensService tokensService;
    private final OpenSeaService openSeaService;

    public Erc721AssetsViewModelFactory(FetchTransactionsInteract fetchTransactionsInteract,
                                        AssetDefinitionService assetDefinitionService,
                                        TokensService tokensService,
                                        OpenSeaService openSeaService) {
        this.fetchTransactionsInteract = fetchTransactionsInteract;
        this.assetDefinitionService = assetDefinitionService;
        this.tokensService = tokensService;
        this.openSeaService = openSeaService;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        return (T) new Erc721AssetsViewModel(fetchTransactionsInteract, assetDefinitionService, tokensService, openSeaService);
    }
}
