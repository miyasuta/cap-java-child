using { my.book as book } from '../db/schema';

@requires: 'any'
service CatalogService {
  entity Books as projection on book.Books;
}
